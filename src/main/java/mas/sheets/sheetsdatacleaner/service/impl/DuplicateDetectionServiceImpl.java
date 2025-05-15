package mas.sheets.sheetsdatacleaner.service.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    /* ── пороги (остаются без изменений) ───────────────────────────────── */

    private static final double HARD_REJECT_THRESHOLD = 0.25;
    private static final double FAST_REJECT_THRESHOLD = 0.35;
    private static final double FAST_CONFIRM_LONG = 0.55;
    private static final double FAST_CONFIRM_SHORT = 0.70;
    private static final int SHORT_LEN_LIMIT = 30;

    private static final double NEURAL_CONFIRM_THRESHOLD = 0.78;

    /* ── веса эвристических скореров ──────────────────────────────────── */

    private static final double TOKEN_WEIGHT = 0.60;
    private static final double LEV_WEIGHT = 0.25;
    private static final double JW_WEIGHT = 0.10;

    /* ── параллельность ───────────────────────────────────────────────── */

    private static final int NEURAL_BATCH_SIZE = 50;
    private static final int CORE = Runtime.getRuntime().availableProcessors();
    private static final int MAX_IN_FLIGHT = 4_000;

    /* ── зависимости ─────────────────────────────────────────────────── */

    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    private final BlockingQueue<Runnable> queue =
            new ArrayBlockingQueue<>(MAX_IN_FLIGHT);

    private final ExecutorService workers =
            new ThreadPoolExecutor(CORE * 2, CORE * 2,
                    0L, TimeUnit.SECONDS, queue);

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

    /* =================================================================== */
    /*                           PIPELINE                                  */
    /* =================================================================== */

    @Override
    @Bulkhead(name = "duplicateDetector", type = Bulkhead.Type.SEMAPHORE)
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {

        log.info("PIPELINE-START: rows={} RAW={}", request.rows().size(), request.rows());

        /* 1️⃣  нормализация ------------------------------------------------ */
        List<String> normalized = normalizer.normalizeRows(request.rows());
        log.info("PIPELINE-1: normalized={} DATA={}", normalized.size(), normalized);

        /* 2️⃣  точные дубликаты ------------------------------------------- */
        var exact = exactDetector.detect(normalized);
        log.info("PIPELINE-2: exactGroups={} remainingRows={} exactGroupsDetailed={}",
                exact.duplicateGroups().size(),
                exact.remainingRows().size(),
                exact.duplicateGroups().stream()
                        .map(gr -> gr.stream().map(normalized::get).toList())
                        .toList());

        Set<IndexPair> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups())
            for (int i = 0; i < g.size(); i++)
                for (int j = i + 1; j < g.size(); j++)
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));

        log.info("PIPELINE-2.1: confirmedPairs={}", confirmed.size());

        if (exact.remainingRows().isEmpty()) {
            log.info("PIPELINE-END: onlyExact=true");
            return new DuplicateMatchResponse(confirmed, probable);
        }

        /* 3️⃣  генерация MinHash кандидатов ------------------------------- */
        List<String> restRows = exact.remainingRows();
        List<Integer> restIdx = exact.originalIndexes();

        Set<IndexPair> pairs = candidateGenerator.generateCandidatePairs(restRows);

        log.info("PIPELINE-3: candidates={} DATA={}", pairs.size(),
                pairs.stream()
                        .map(p -> Map.of("idx1", restIdx.get(p.first()),
                                "idx2", restIdx.get(p.second()),
                                "left", restRows.get(p.first()),
                                "right", restRows.get(p.second())))
                        .toList());

        if (pairs.isEmpty()) {
            log.info("PIPELINE-END: noCandidates=true");
            return new DuplicateMatchResponse(confirmed, probable);
        }

        /* 3.1  эвристический скоринг ------------------------------------- */
        List<PairEval> toNeural = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(pairs.size());
        AtomicInteger hardRejected = new AtomicInteger();
        AtomicInteger fastConfirmed = new AtomicInteger();

        for (IndexPair p : pairs) {
            Runnable job = () -> {
                try {
                    PairEval ev = evaluatePair(p, restRows);

                    if (ev.weightedScore <= HARD_REJECT_THRESHOLD) {
                        hardRejected.incrementAndGet();
                        return;
                    }

                    double confirmThr = (ev.maxLen < SHORT_LEN_LIMIT)
                            ? FAST_CONFIRM_SHORT
                            : FAST_CONFIRM_LONG;

                    if (ev.weightedScore >= confirmThr) {
                        probable.add(mapOriginal(p, restIdx));
                        fastConfirmed.incrementAndGet();
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
                job.run();                      // caller-runs fallback
            }
        }

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.info("PIPELINE-3.X: hardRejected={} fastConfirmed={} toNeural={} toNeuralDATA={}",
                hardRejected.get(), fastConfirmed.get(), toNeural.size(),
                toNeural.stream()
                        .map(e -> Map.of("idx1", restIdx.get(e.pair.first()),
                                "idx2", restIdx.get(e.pair.second()),
                                "score", String.format(Locale.ROOT, "%.3f", e.weightedScore),
                                "left", restRows.get(e.pair.first()),
                                "right", restRows.get(e.pair.second())))
                        .toList());

        /* 4️⃣  нейросеть --------------------------------------------------- */
        if (!toNeural.isEmpty()) runNeuralStage(toNeural, restRows, restIdx, probable);

        /* 5️⃣  финал ------------------------------------------------------- */
        log.info("PIPELINE-END: confirmedTotal={} probableTotal={}",
                confirmed.size(), probable.size());

        return new DuplicateMatchResponse(confirmed, probable);
    }

    /* =================================================================== */
    /*                        PRIVATE HELPERS                              */
    /* =================================================================== */

    private PairEval evaluatePair(IndexPair pair, List<String> rows) {

        String left = rows.get(pair.first());
        String right = rows.get(pair.second());

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
                                List<String> rows,
                                List<Integer> originalIdx,
                                Set<IndexPair> probable) {

        AtomicInteger confirmedByNN = new AtomicInteger();

        for (int i = 0; i < batchList.size(); i += NEURAL_BATCH_SIZE) {
            int to = Math.min(i + NEURAL_BATCH_SIZE, batchList.size());
            List<PairEval> slice = batchList.subList(i, to);

            Runnable r = () -> {
                List<Pair<String, String>> q = slice.stream()
                        .map(e -> Pair.of(rows.get(e.pair.first()),
                                rows.get(e.pair.second())))
                        .collect(Collectors.toList());

                Map<Pair<String, String>, Double> scores =
                        neuralService.fetchBatchSimilarityScores(q);

                int added = 0;
                for (int k = 0; k < slice.size(); k++) {
                    if (scores.getOrDefault(q.get(k), 0.0) >= NEURAL_CONFIRM_THRESHOLD) {
                        probable.add(mapOriginal(slice.get(k).pair, originalIdx));
                        added++;
                    }
                }
                confirmedByNN.addAndGet(added);
            };
            CompletableFuture.runAsync(r, neuralPool).join();
        }
        log.info("NEURAL: confirmed={} / {}", confirmedByNN.get(), batchList.size());
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    /* DTO внутри сервиса */
    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
