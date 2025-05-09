package mas.sheets.sheetsdatacleaner.service.impl;

import jakarta.annotation.PreDestroy;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.*;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.JaroWinklerScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final double HARD_REJECT_THRESHOLD = 0.25;
    private static final double FAST_REJECT_THRESHOLD = 0.30;
    private static final double FAST_CONFIRM_THRESHOLD = 0.55;
    private static final double NEURAL_CONFIRM_THRESHOLD = 0.70;

    private static final double TOKEN_WEIGHT = 0.60;
    private static final double LEV_WEIGHT = 0.25;
    private static final double JW_WEIGHT = 0.10;

    private static final int NEURAL_BATCH_SIZE = 50;
    private static final int POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    private final ExecutorService scorerPool = Executors.newFixedThreadPool(POOL_SIZE);
    private final ExecutorService neuralPool = Executors.newFixedThreadPool(Math.min(4, POOL_SIZE));

    public DuplicateDetectionServiceImpl(
            ExactDuplicateDetector exactDetector,
            MinHashCandidateDetectionService candidateGenerator,
            RowNormalizerService normalizer,
            @Qualifier("heuristicScorers") List<SimilarityScorer> scorers,
            NeuralSimilarityService neuralService) {
        this.exactDetector = exactDetector;
        this.candidateGenerator = candidateGenerator;
        this.normalizer = normalizer;
        this.scorers = scorers;
        this.neuralService = neuralService;
    }

    @PreDestroy
    public void shutdown() {
        scorerPool.shutdown();
        neuralPool.shutdown();
    }

    @Override
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {

        List<String> normalized = normalizer.normalizeRows(request.rows());

        ExactDuplicateDetectorImpl.ExactDetectionResult exact = exactDetector.detect(normalized);

        Set<IndexPair<Integer, Integer>> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair<Integer, Integer>> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> group : exact.duplicateGroups()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    confirmed.add(IndexPair.ofNormalized(group.get(i), group.get(j)));
                }
            }
        }

        if (exact.remainingRows().isEmpty()) {
            return new DuplicateMatchResponse(confirmed, probable);
        }

        List<String> remainingRows = exact.remainingRows();
        List<Integer> originalIndexes = exact.originalIndexes();

        Set<IndexPair<Integer, Integer>> pairs = candidateGenerator.generateCandidatePairs(remainingRows);

        if (pairs.isEmpty()) {
            return new DuplicateMatchResponse(confirmed, probable);
        }

        List<PairEval> evaluations = pairs.parallelStream()
                .map(pair -> CompletableFuture.supplyAsync(() -> evaluatePair(pair, remainingRows), scorerPool))
                .map(CompletableFuture::join)
                .toList();

        List<PairEval> toNeural = new ArrayList<>();
        for (PairEval evaluation : evaluations) {

            IndexPair<Integer, Integer> pair = evaluation.pair;

            if (evaluation.minScore <= HARD_REJECT_THRESHOLD) {
                continue;
            }

            if (evaluation.weightedScore >= FAST_CONFIRM_THRESHOLD) {
                probable.add(mapOriginal(pair, originalIndexes));
            } else if (evaluation.weightedScore > FAST_REJECT_THRESHOLD) {
                toNeural.add(evaluation);
            }
        }

        if (!toNeural.isEmpty()) {
            List<List<PairEval>> batches = new ArrayList<>();
            for (int i = 0; i < toNeural.size(); i += NEURAL_BATCH_SIZE) {
                batches.add(toNeural.subList(i, Math.min(i + NEURAL_BATCH_SIZE, toNeural.size())));
            }

            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(
                            () -> processBatch(batch, remainingRows, originalIndexes, probable), neuralPool))
                    .toList();
            futures.forEach(CompletableFuture::join);
        }

        return new DuplicateMatchResponse(confirmed, probable);
    }

    private PairEval evaluatePair(IndexPair<Integer, Integer> pair, List<String> rows) {
        String left = rows.get(pair.first());
        String right = rows.get(pair.second());

        int maxLen = Math.max(left.length(), right.length());
        if (maxLen > 0 && Math.abs(left.length() - right.length()) / (double) maxLen > 0.5) {
            return new PairEval(pair, 0.0, 0.0);
        }

        double token = 0.0;
        double lev = 0.0;
        double jw = 0.0;

        for (SimilarityScorer scorer : scorers) {
            if (scorer instanceof TokenSetRatioScorer) {
                token = scorer.calculateScore(left, right);
            } else if (scorer instanceof LevenshteinScorer) {
                lev = scorer.calculateScore(left, right);
            } else if (scorer instanceof JaroWinklerScorer) {
                jw = scorer.calculateScore(left, right);
            }
        }

        int used = 0;
        if (token > 0.0) {
            used++;
        }
        if (lev > 0.0) {
            used++;
        }
        if (jw > 0.0) {
            used++;
        }

        double avgScore = used > 0 ? (token + lev + jw) / used : 0.0;
        double weightedScore = TOKEN_WEIGHT * token + LEV_WEIGHT * lev + JW_WEIGHT * jw;

        return new PairEval(pair, avgScore, weightedScore);
    }

    private void processBatch(List<PairEval> batch,
                              List<String> rows,
                              List<Integer> indexes,
                              Set<IndexPair<Integer, Integer>> probable) {

        List<Pair<String, String>> query = batch.stream()
                .map(evaluation -> Pair.of(rows.get(evaluation.pair.first()), rows.get(evaluation.pair.second())))
                .toList();

        Map<Pair<String, String>, Double> scores = neuralService.fetchBatchSimilarityScores(query);

        for (int i = 0; i < batch.size(); i++) {
            IndexPair<Integer, Integer> pair = batch.get(i).pair;
            double score = scores.getOrDefault(query.get(i), 0.0);

            if (score >= NEURAL_CONFIRM_THRESHOLD) {
                probable.add(mapOriginal(pair, indexes));
            }
        }
    }

    private IndexPair<Integer, Integer> mapOriginal(IndexPair<Integer, Integer> pair, List<Integer> indexes) {
        return IndexPair.ofNormalized(indexes.get(pair.first()), indexes.get(pair.second()));
    }

    private record PairEval(IndexPair<Integer, Integer> pair, double minScore, double weightedScore) {
    }
}
