package mas.sheets.sheetsdatacleaner.service.impl;

import jakarta.annotation.PreDestroy;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.service.*;
import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.JaroWinklerScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
    private static final int CORE = Runtime.getRuntime().availableProcessors();
    private static final int MAX_IN_FLIGHT = 4_000;

    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    private final BlockingQueue<Runnable> queue =
            new ArrayBlockingQueue<>(MAX_IN_FLIGHT);

    private final ExecutorService workers =
            new ThreadPoolExecutor(CORE * 2, CORE * 2, 0L,
                    TimeUnit.SECONDS, queue);

    private final ExecutorService neuralPool =
            Executors.newFixedThreadPool(Math.min(4, CORE));

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
        workers.shutdown();
        neuralPool.shutdown();
    }

    @Override
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {

        List<String> normalized = normalizer.normalizeRows(request.rows());

        var exact = exactDetector.detect(normalized);

        Set<IndexPair> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups()) {
            for (int i = 0; i < g.size(); i++) {
                for (int j = i + 1; j < g.size(); j++) {
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));
                }
            }
        }

        if (exact.remainingRows().isEmpty()) {
            return new DuplicateMatchResponse(confirmed, probable);
        }

        List<String> restRows = exact.remainingRows();
        List<Integer> restIdx = exact.originalIndexes();

        Set<IndexPair> pairs = candidateGenerator.generateCandidatePairs(restRows);
        if (pairs.isEmpty()) {
            return new DuplicateMatchResponse(confirmed, probable);
        }

        List<PairEval> toNeural = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(pairs.size());

        for (IndexPair p : pairs) {
            workers.execute(() -> {
                try {
                    PairEval e = evaluatePair(p, restRows);
                    if (e.avgScore <= HARD_REJECT_THRESHOLD) return;
                    if (e.weightedScore >= FAST_CONFIRM_THRESHOLD) {
                        probable.add(mapOriginal(p, restIdx));
                    } else if (e.weightedScore > FAST_REJECT_THRESHOLD) {
                        toNeural.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (!toNeural.isEmpty()) {
            for (int i = 0; i < toNeural.size(); i += NEURAL_BATCH_SIZE) {
                int to = Math.min(i + NEURAL_BATCH_SIZE, toNeural.size());
                List<PairEval> batch = toNeural.subList(i, to);
                CompletableFuture.runAsync(() ->
                        processBatch(batch, restRows, restIdx, probable), neuralPool).join();
            }
        }

        return new DuplicateMatchResponse(confirmed, probable);
    }

    private PairEval evaluatePair(IndexPair pair, List<String> rows) {
        String left = rows.get(pair.first());
        String right = rows.get(pair.second());

        int maxLen = Math.max(left.length(), right.length());
        if (maxLen > 0 && Math.abs(left.length() - right.length()) / (double) maxLen > 0.5) {
            return new PairEval(pair, 0.0, 0.0);
        }

        double token = 0, lev = 0, jw = 0;
        for (SimilarityScorer s : scorers) {
            if (s instanceof TokenSetRatioScorer) token = s.calculateScore(left, right);
            else if (s instanceof LevenshteinScorer) lev = s.calculateScore(left, right);
            else if (s instanceof JaroWinklerScorer) jw = s.calculateScore(left, right);
        }

        int used = 0;
        if (token > 0) used++;
        if (lev > 0) used++;
        if (jw > 0) used++;

        double avg = used > 0 ? (token + lev + jw) / used : 0.0;
        double weighted = TOKEN_WEIGHT * token + LEV_WEIGHT * lev + JW_WEIGHT * jw;

        return new PairEval(pair, avg, weighted);
    }

    private void processBatch(List<PairEval> batch,
                              List<String> rows,
                              List<Integer> idx,
                              Set<IndexPair> probable) {

        List<Pair<String, String>> q = batch.stream()
                .map(e -> Pair.of(rows.get(e.pair.first()),
                        rows.get(e.pair.second())))
                .collect(Collectors.toList());

        Map<Pair<String, String>, Double> scores =
                neuralService.fetchBatchSimilarityScores(q);

        for (int i = 0; i < batch.size(); i++) {
            IndexPair p = batch.get(i).pair;
            if (scores.getOrDefault(q.get(i), 0.0) >= NEURAL_CONFIRM_THRESHOLD) {
                probable.add(mapOriginal(p, idx));
            }
        }
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    private record PairEval(IndexPair pair, double avgScore, double weightedScore) {
    }
}
