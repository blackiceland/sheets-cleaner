package mas.sheets.sheetsdatacleaner.service.impl;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
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

        log.info("START");

        /* ---------- 1. Нормализация ---------- */
        List<String> normalized = normalizer.normalizeRows(request.rows());
        for (int i = 0; i < normalized.size(); i++) {
            log.info("NORM  idx={}   '{}'", i, normalized.get(i));
        }

        /* ---------- 2. Точные дубликаты ---------- */
        ExactDuplicateDetectorImpl.ExactDetectionResult exact = exactDetector.detect(normalized);

        Set<IndexPair<Integer, Integer>> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair<Integer, Integer>> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups()) {
            for (int i = 0; i < g.size(); i++) {
                for (int j = i + 1; j < g.size(); j++) {
                    IndexPair<Integer, Integer> p = IndexPair.ofNormalized(g.get(i), g.get(j));
                    confirmed.add(p);
                    log.info("EXACT  {}  '{}'  <->  {}  '{}'",
                            p.first(), normalized.get(p.first()),
                            p.second(), normalized.get(p.second()));
                }
            }
        }

        if (exact.remainingRows().isEmpty()) {
            log.info("FINISH");
            return new DuplicateMatchResponse(confirmed, probable);
        }

        /* ---------- 3. MinHash кандидаты ---------- */
        List<String> restRows = exact.remainingRows();
        List<Integer> restIdx = exact.originalIndexes();

        Set<IndexPair<Integer, Integer>> pairs = candidateGenerator.generateCandidatePairs(restRows);

        for (IndexPair<Integer, Integer> p : pairs) {
            log.info("MINHASH  {}  '{}'  <->  {}  '{}'",
                    restIdx.get(p.first()), restRows.get(p.first()),
                    restIdx.get(p.second()), restRows.get(p.second()));
        }

        if (pairs.isEmpty()) {
            log.info("FINISH");
            return new DuplicateMatchResponse(confirmed, probable);
        }

        /* ---------- 4. Эвристики ---------- */
        List<PairEval> evaluations = pairs.parallelStream()
                .map(p -> CompletableFuture.supplyAsync(() -> evaluatePair(p, restRows), scorerPool))
                .map(CompletableFuture::join)
                .toList();

        List<PairEval> toNeural = new ArrayList<>();
        for (PairEval e : evaluations) {

            IndexPair<Integer, Integer> p = e.pair;
            int idxA = restIdx.get(p.first());
            int idxB = restIdx.get(p.second());
            String a = restRows.get(p.first());
            String b = restRows.get(p.second());

            if (e.minScore <= HARD_REJECT_THRESHOLD) {
                log.info("HARD_REJECT  {} '{}'  <->  {}  '{}'   min={}", idxA, a, idxB, b, e.minScore);
                continue;
            }

            if (e.weightedScore >= FAST_CONFIRM_THRESHOLD) {
                probable.add(mapOriginal(p, restIdx));
                log.info("FAST_CONFIRM {} '{}'  <->  {} '{}'   score={}", idxA, a, idxB, b, e.weightedScore);
            } else if (e.weightedScore > FAST_REJECT_THRESHOLD) {
                toNeural.add(e);
                log.info("TO_NEURAL    {} '{}'  <->  {} '{}'   score={}", idxA, a, idxB, b, e.weightedScore);
            } else {
                log.info("FAST_REJECT  {} '{}'  <->  {} '{}'   score={}", idxA, a, idxB, b, e.weightedScore);
            }
        }

        /* ---------- 5. Нейросеть ---------- */
        if (!toNeural.isEmpty()) {
            List<List<PairEval>> batches = new ArrayList<>();
            for (int i = 0; i < toNeural.size(); i += NEURAL_BATCH_SIZE) {
                batches.add(toNeural.subList(i, Math.min(i + NEURAL_BATCH_SIZE, toNeural.size())));
            }

            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(b -> CompletableFuture.runAsync(() -> processBatch(b, restRows, restIdx, probable), neuralPool))
                    .toList();
            futures.forEach(CompletableFuture::join);
        }

        log.info("FINISH");
        return new DuplicateMatchResponse(confirmed, probable);
    }

    /* ======= вспомогательные методы ======= */

    private PairEval evaluatePair(IndexPair<Integer, Integer> pair, List<String> rows) {
        String left = rows.get(pair.first());
        String right = rows.get(pair.second());

        int maxLen = Math.max(left.length(), right.length());
        if (maxLen > 0 && Math.abs(left.length() - right.length()) / (double) maxLen > 0.5) {
            return new PairEval(pair, 0.0, 0.0);
        }

        double token = 0.0, lev = 0.0, jw = 0.0;

        for (SimilarityScorer s : scorers) {
            if (s instanceof TokenSetRatioScorer) token = s.calculateScore(left, right);
            else if (s instanceof LevenshteinScorer) lev = s.calculateScore(left, right);
            else if (s instanceof JaroWinklerScorer) jw = s.calculateScore(left, right);
        }

        int used = 0;
        if (token > 0.0) used++;
        if (lev > 0.0) used++;
        if (jw > 0.0) used++;

        double avgScore = used > 0 ? (token + lev + jw) / used : 0.0;
        double weightedScore = TOKEN_WEIGHT * token + LEV_WEIGHT * lev + JW_WEIGHT * jw;

        return new PairEval(pair, avgScore, weightedScore);
    }


    private void processBatch(List<PairEval> batch,
                              List<String> rows,
                              List<Integer> idx,
                              Set<IndexPair<Integer, Integer>> probable) {

        List<Pair<String, String>> query = batch.stream()
                .map(e -> Pair.of(rows.get(e.pair.first()), rows.get(e.pair.second())))
                .toList();

        Map<Pair<String, String>, Double> scores = neuralService.fetchBatchSimilarityScores(query);

        for (int i = 0; i < batch.size(); i++) {
            IndexPair<Integer, Integer> p = batch.get(i).pair;
            double score = scores.getOrDefault(query.get(i), 0.0);

            int origA = idx.get(p.first());
            int origB = idx.get(p.second());
            String a = rows.get(p.first());
            String b = rows.get(p.second());

            if (score >= NEURAL_CONFIRM_THRESHOLD) {
                probable.add(mapOriginal(p, idx));
                log.info("NEURAL_CONFIRM {} '{}'  <->  {} '{}'   prob={}", origA, a, origB, b, score);
            } else {
                log.info("NEURAL_REJECT  {} '{}'  <->  {} '{}'   prob={}", origA, a, origB, b, score);
            }
        }
    }

    private IndexPair<Integer, Integer> mapOriginal(IndexPair<Integer, Integer> p, List<Integer> idx) {
        return IndexPair.ofNormalized(idx.get(p.first()), idx.get(p.second()));
    }

    private record PairEval(IndexPair<Integer, Integer> pair, double minScore, double weightedScore) {
    }
}
