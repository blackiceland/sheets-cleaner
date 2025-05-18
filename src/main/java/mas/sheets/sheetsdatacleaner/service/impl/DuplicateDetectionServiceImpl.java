package mas.sheets.sheetsdatacleaner.service.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
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
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final double HARD_REJECT_SHORT = 0.15;
    private static final double HARD_REJECT_LONG = 0.25;
    private static final double FAST_REJECT_THRESHOLD = 0.35;
    private static final double FAST_CONFIRM_SHORT = 0.65;
    private static final double FAST_CONFIRM_LONG = 0.55;
    private static final int SHORT_LEN_LIMIT = 30;
    private static final int NN_LEN_LIMIT = 20;
    private static final double NN_CONFIRM_SHORT = 0.65;
    private static final double NN_CONFIRM_LONG = 0.78;

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

    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(MAX_IN_FLIGHT);
    private final ExecutorService workers = new ThreadPoolExecutor(CORE * 2, CORE * 2, 0L, TimeUnit.SECONDS, queue);
    private final ExecutorService neuralPool = Executors.newFixedThreadPool(Math.min(4, CORE));

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
    @Bulkhead(name = "duplicateDetector", type = Bulkhead.Type.SEMAPHORE)
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {
        List<RowNorm> normalized = normalizer.normalizeRows(request.rows());
        ExactDetectionResult exact = exactDetector.detect(normalized);

        Set<IndexPair> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups())
            for (int i = 0; i < g.size(); i++)
                for (int j = i + 1; j < g.size(); j++)
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));

        if (exact.remainingRows().isEmpty())
            return new DuplicateMatchResponse(confirmed, probable);

        List<RowNorm> restRows = exact.remainingRows();
        List<Integer> restIdx = exact.originalIndexes();
        Set<IndexPair> pairs = candidateGenerator.generateCandidatePairs(restRows);

        if (pairs.isEmpty())
            return new DuplicateMatchResponse(confirmed, probable);

        List<PairEval> toNeural = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(pairs.size());

        for (IndexPair p : pairs) {
            Runnable job = () -> {
                try {
                    PairEval ev = evaluatePair(p, restRows);

                    double hardThr = (ev.maxLen < 15) ? HARD_REJECT_SHORT : HARD_REJECT_LONG;

                    if (ev.weightedScore <= hardThr)
                        return;

                    double confirmThr = (ev.maxLen < SHORT_LEN_LIMIT) ? FAST_CONFIRM_SHORT : FAST_CONFIRM_LONG;
                    if (ev.weightedScore >= confirmThr) {
                        probable.add(mapOriginal(p, restIdx));
                    } else if (ev.weightedScore > FAST_REJECT_THRESHOLD) {
                        toNeural.add(ev);
                    }
                } finally {
                    latch.countDown();
                }
            };

            try {
                workers.execute(job);
            } catch (RejectedExecutionException ex) {
                job.run();
            }
        }

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        if (!toNeural.isEmpty())
            runNeuralStage(toNeural, restRows, restIdx, probable);

        log.info("duplicates: exact={}, nnConfirmed={}, probable={}",
                confirmed.size(),
                probable.size() - confirmed.size(),
                probable.size());

        return new DuplicateMatchResponse(confirmed, probable);
    }

    private PairEval evaluatePair(IndexPair pair, List<RowNorm> rows) {
        String left = rows.get(pair.first()).value();
        String right = rows.get(pair.second()).value();

        double token = 0, lev = 0, jw = 0;

        for (SimilarityScorer s : scorers) {
            if (s instanceof TokenSetRatioScorer) token = s.calculateScore(left, right);
            else if (s instanceof LevenshteinScorer) lev = s.calculateScore(left, right);
            else if (s instanceof JaroWinklerScorer) jw = s.calculateScore(left, right);
        }

        double weighted = TOKEN_WEIGHT * token + LEV_WEIGHT * lev + JW_WEIGHT * jw;
        int maxLen = Math.max(left.length(), right.length());

        return new PairEval(pair, weighted, maxLen);
    }

    private void runNeuralStage(List<PairEval> batchList,
                                List<RowNorm> rows,
                                List<Integer> originalIdx,
                                Set<IndexPair> probable) {
        AtomicInteger confirmedByNN = new AtomicInteger();

        for (int i = 0; i < batchList.size(); i += NEURAL_BATCH_SIZE) {
            int to = Math.min(i + NEURAL_BATCH_SIZE, batchList.size());
            List<PairEval> slice = batchList.subList(i, to);

            Runnable r = () -> {
                List<Pair<String, String>> q = slice.stream()
                        .map(e -> Pair.of(rows.get(e.pair.first()).value(),
                                rows.get(e.pair.second()).value()))
                        .toList();

                Map<Pair<String, String>, Double> scores =
                        neuralService.fetchBatchSimilarityScores(q);

                for (int k = 0; k < slice.size(); k++) {
                    PairEval ev = slice.get(k);
                    double score = scores.getOrDefault(q.get(k), 0.0);
                    double thr = (ev.maxLen < NN_LEN_LIMIT) ? NN_CONFIRM_SHORT : NN_CONFIRM_LONG;

                    if (score >= thr) {
                        probable.add(mapOriginal(ev.pair, originalIdx));
                        confirmedByNN.incrementAndGet();
                    }
                }
            };

            CompletableFuture.runAsync(r, neuralPool).join();
        }

        log.info("NN confirmed {} pairs ({} evaluated)", confirmedByNN.get(), batchList.size());
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
