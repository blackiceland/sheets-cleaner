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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    /* ── гибкие пороги ─────────────────────────────────────────────── */
    private static final double HARD_REJECT_SHORT = 0.15;   // строка < 15 симв
    private static final double HARD_REJECT_LONG = 0.25;   // ≥ 15 симв

    private static final double FAST_REJECT_THRESHOLD = 0.35;

    private static final double FAST_CONFIRM_SHORT = 0.65;   // строка < 30 симв
    private static final double FAST_CONFIRM_LONG = 0.55;   // ≥ 30 симв
    private static final int SHORT_LEN_LIMIT = 30;

    private static final int NN_LEN_LIMIT = 20;     // порог для NN
    private static final double NN_CONFIRM_SHORT = 0.65;   // len < 20
    private static final double NN_CONFIRM_LONG = 0.78;   // len ≥ 20

    /* ── веса эвристических скореров ──────────────────────────────── */
    private static final double TOKEN_WEIGHT = 0.60;
    private static final double LEV_WEIGHT = 0.25;
    private static final double JW_WEIGHT = 0.10;

    /* ── параллельность ───────────────────────────────────────────── */
    private static final int NEURAL_BATCH_SIZE = 50;
    private static final int CORE = Runtime.getRuntime().availableProcessors();
    private static final int MAX_IN_FLIGHT = 4_000;

    /* ── зависимости ─────────────────────────────────────────────── */
    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(MAX_IN_FLIGHT);

    private final ExecutorService workers =
            new ThreadPoolExecutor(CORE * 2, CORE * 2, 0L, TimeUnit.SECONDS, queue);

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

    /* ====================================================================== */
    /*                                PIPELINE                                */
    /* ====================================================================== */

    @Override
    @Bulkhead(name = "duplicateDetector", type = Bulkhead.Type.SEMAPHORE)
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {

        /* ---------- RAW INPUT ---------- */
        log.info("=================================================================================");
        log.info(
                "PIPELINE-START: rows={}\nROWS:\n{}",
                request.rows().size(),
                IntStream.range(0, request.rows().size())
                        .mapToObj(i -> i + ": " + String.join(" | ", request.rows().get(i)))
                        .collect(Collectors.joining(System.lineSeparator()))
        );

        /* 1️⃣  НОРМАЛИЗАЦИЯ ------------------------------------------------ */
        List<RowNorm> normalized = normalizer.normalizeRows(request.rows());

        log.info("=================================================================================");
        log.info(
                "PIPELINE-1: normalized={}\nNORMALIZED:\n{}",
                normalized.size(),
                normalized.stream()
                        .map(RowNorm::value)
                        .collect(Collectors.joining(System.lineSeparator()))
        );

        /* 2️⃣  ТОЧНЫЕ ДУБЛИКАТЫ ------------------------------------------- */
        ExactDetectionResult exact = exactDetector.detect(normalized);


        Map<Integer, RowNorm> normByIdx = normalized.stream()
                .collect(Collectors.toMap(RowNorm::idx, rn -> rn));

        log.info("=================================================================================");
        log.info("PIPELINE-2: exactGroups={}  remainingRows={}",
                exact.duplicateGroups().size(),
                exact.remainingRows().size());

        exact.duplicateGroups().forEach(gr -> {
            String joined = gr.stream()
                    .sorted()
                    .map(idx -> String.format("[%d] %s",
                            idx,
                            normByIdx.get(idx).value()))
                    .collect(Collectors.joining(", "));
            log.info("GROUP {}", joined);
        });

        /* confirmed из точных групп */
        Set<IndexPair> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups())
            for (int i = 0; i < g.size(); i++)
                for (int j = i + 1; j < g.size(); j++)
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));

        log.info("=================================================================================");
        log.info("PIPELINE-2.1: confirmedPairs={}", confirmed.size());

        if (exact.remainingRows().isEmpty()) {
            log.info("PIPELINE-END: onlyExact=true");
            return new DuplicateMatchResponse(confirmed, probable);
        }

        /* 3️⃣  MinHash КАНДИДАТЫ ----------------------------------------- */
        List<RowNorm> restRows = exact.remainingRows();   // уже RowNorm
        List<Integer> restIdx = exact.originalIndexes(); // нужен для mapOriginal

        Set<IndexPair> pairs = candidateGenerator.generateCandidatePairs(restRows);

        log.info("=================================================================================");
        log.info(
                "PIPELINE-3: candidates={}\nPairs:\n{}",
                pairs.size(),
                pairs.stream()
                        .map(p -> String.format("[%d] %s  <->  [%d] %s",
                                restIdx.get(p.first()), restRows.get(p.first()).value(),
                                restIdx.get(p.second()), restRows.get(p.second()).value()))
                        .collect(Collectors.joining(System.lineSeparator()))
        );

        if (pairs.isEmpty()) {
            log.info("PIPELINE-END: noCandidates=true");
            return new DuplicateMatchResponse(confirmed, probable);
        }

        /* 3.1  ЭВРИСТИКА -------------------------------------------------- */
        List<PairEval> toNeural = new CopyOnWriteArrayList<>();
        List<IndexPair> hardRejectPairs = new CopyOnWriteArrayList<>();
        List<IndexPair> fastConfirmPairs = new CopyOnWriteArrayList<>();

        CountDownLatch latch = new CountDownLatch(pairs.size());
        AtomicInteger hardRejected = new AtomicInteger();
        AtomicInteger fastConfirmed = new AtomicInteger();

        for (IndexPair p : pairs) {
            Runnable job = () -> {
                try {
                    PairEval ev = evaluatePair(p, restRows);

                    /* --- Hard-reject с гибким порогом --- */
                    double hardThr = (ev.maxLen < 15) ? HARD_REJECT_SHORT : HARD_REJECT_LONG;
                    if (ev.weightedScore <= hardThr) {
                        hardRejected.incrementAndGet();
                        hardRejectPairs.add(p);
                        return;
                    }

                    /* --- Fast-confirm с гибким порогом --- */
                    double confirmThr = (ev.maxLen < SHORT_LEN_LIMIT)
                            ? FAST_CONFIRM_SHORT : FAST_CONFIRM_LONG;

                    if (ev.weightedScore >= confirmThr) {
                        probable.add(mapOriginal(p, restIdx));
                        fastConfirmed.incrementAndGet();
                        fastConfirmPairs.add(p);
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
                job.run(); // caller-runs fallback
            }
        }

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.info("=================================================================================");
        log.info("""
                        PIPELINE-3.X: hardRejected={}  fastConfirmed={}  toNeural={}
                        ── HARD-REJECT ({}) ──
                        {}
                        ── FAST-CONFIRM ({}) ──
                        {}
                        ── TO-NEURAL ({}) ──
                        {}""",
                hardRejected.get(), fastConfirmed.get(), toNeural.size(),

                hardRejectPairs.size(),
                hardRejectPairs.stream()
                        .map(p -> String.format("[%d] %s  ↛  [%d] %s",
                                restIdx.get(p.first()), restRows.get(p.first()).value(),
                                restIdx.get(p.second()), restRows.get(p.second()).value()))
                        .collect(Collectors.joining(System.lineSeparator())),

                fastConfirmPairs.size(),
                fastConfirmPairs.stream()
                        .map(p -> String.format("[%d] %s  ==  [%d] %s",
                                restIdx.get(p.first()), restRows.get(p.first()).value(),
                                restIdx.get(p.second()), restRows.get(p.second()).value()))
                        .collect(Collectors.joining(System.lineSeparator())),

                toNeural.size(),
                toNeural.stream()
                        .map(e -> String.format(Locale.ROOT,
                                "[%d] %s  ??  [%d] %s   (score=%.3f)",
                                restIdx.get(e.pair.first()), restRows.get(e.pair.first()).value(),
                                restIdx.get(e.pair.second()), restRows.get(e.pair.second()).value(),
                                e.weightedScore))
                        .collect(Collectors.joining(System.lineSeparator()))
        );

        /* 4️⃣  НЕЙРОСЕТЬ --------------------------------------------------- */
        if (!toNeural.isEmpty())
            runNeuralStage(toNeural, restRows, restIdx, probable);

        /* 5️⃣  ФИНАЛ ------------------------------------------------------- */
        log.info("=================================================================================");
        log.info(
                "PIPELINE-END: confirmedTotal={} probableTotal={}\n"
                        + "Confirmed pairs:\n{}\n"
                        + "Probable pairs:\n{}",
                confirmed.size(), probable.size(),

                confirmed.stream()
                        .map(p -> String.format("[%-4d] %s  <->  [%-4d] %s",
                                p.first(), String.join(" | ", request.rows().get(p.first())),
                                p.second(), String.join(" | ", request.rows().get(p.second()))))
                        .collect(Collectors.joining(System.lineSeparator())),

                probable.stream()
                        .map(p -> String.format("[%-4d] %s  <->  [%-4d] %s",
                                p.first(), String.join(" | ", request.rows().get(p.first())),
                                p.second(), String.join(" | ", request.rows().get(p.second()))))
                        .collect(Collectors.joining(System.lineSeparator()))
        );
        return new DuplicateMatchResponse(confirmed, probable);
    }

    /* ====================================================================== */
    /*                               HELPERS                                  */
    /* ====================================================================== */

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
                        .collect(Collectors.toList());

                Map<Pair<String, String>, Double> scores =
                        neuralService.fetchBatchSimilarityScores(q);

                int added = 0;
                for (int k = 0; k < slice.size(); k++) {
                    PairEval ev = slice.get(k);
                    double thr = (ev.maxLen < NN_LEN_LIMIT)
                            ? NN_CONFIRM_SHORT
                            : NN_CONFIRM_LONG;

                    if (scores.getOrDefault(q.get(k), 0.0) >= thr) {
                        probable.add(mapOriginal(ev.pair, originalIdx));
                        added++;
                    }
                }
                confirmedByNN.addAndGet(added);
            };
            CompletableFuture.runAsync(r, neuralPool).join();
        }
        log.info("=================================================================================");
        log.info("NEURAL: confirmed={} / {}", confirmedByNN.get(), batchList.size());
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    /* DTO для эвристического этапа */
    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
