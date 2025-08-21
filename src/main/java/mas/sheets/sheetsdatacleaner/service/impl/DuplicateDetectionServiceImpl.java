package mas.sheets.sheetsdatacleaner.service.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.properties.DuplicateDetectorProps;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.enums.ClusterKind;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.JaroWinklerScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private final DuplicateDetectorProps props;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;
    private final NeuralGateService neuralGate;
    private final ThreadPoolTaskExecutor workers;
    private final ExecutorService neuralPool;

    public DuplicateDetectionServiceImpl(
            DuplicateDetectorProps props,
            MinHashCandidateDetectionService candidateGenerator,
            @Qualifier("heuristicScorers") List<SimilarityScorer> scorers,
            NeuralSimilarityService neuralService,
            NeuralGateService neuralGate,
            @Qualifier("workPool") ThreadPoolTaskExecutor workers) {

        this.props = props;
        this.candidateGenerator = candidateGenerator;
        this.scorers = scorers;
        this.neuralService = neuralService;
        this.neuralGate = neuralGate;
        this.workers = workers;

        int core = Runtime.getRuntime().availableProcessors();
        this.neuralPool = Executors.newFixedThreadPool(Math.min(4, core));
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdown();
        neuralPool.shutdown();
    }

    @Bulkhead(name = "duplicateDetector", type = Bulkhead.Type.SEMAPHORE)
    @Override
    public DuplicateMatchResponse detectFuzzy(List<RowNorm> normalized, ExactDetectionResult exact) {
        if (normalized == null) normalized = List.of();
        if (exact == null) exact = new ExactDetectionResult(List.of(), List.of(), List.of(), Map.of());

        Set<IndexPair> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups())
            for (int i = 0; i < g.size(); i++)
                for (int j = i + 1; j < g.size(); j++)
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));

        List<RowNorm> restRows = new ArrayList<>(exact.remainRows());
        List<Integer> restIdx = new ArrayList<>(exact.remainIdxSrc());

        if (!exact.metaByIdx().isEmpty()) {
            Map<Integer, RowNorm> byId = normalized.stream()
                    .collect(Collectors.toMap(RowNorm::idx, r -> r));
            for (RowMeta m : exact.metaByIdx().values()) {
                if (m.kind() == ClusterKind.CANON && !restIdx.contains(m.idx())) {
                    RowNorm r = byId.get(m.idx());
                    if (r != null) {
                        restRows.add(r);
                        restIdx.add(m.idx());
                    }
                }
            }
        }

        ConcurrentHashMap<Integer, RowMeta> meta = new ConcurrentHashMap<>(exact.metaByIdx());

        if (restRows.isEmpty())
            return new DuplicateMatchResponse(confirmed, probable, new ArrayList<>(meta.values()));

        log.info("[fuzzy] candidates input rows={}", restRows.size());

        Set<IndexPair> pairs = candidateGenerator.generateCandidatePairs(restRows);

        log.info("[fuzzy] candidatePairs after MinHash={}", pairs.size());

        if (pairs.isEmpty()) {
            return new DuplicateMatchResponse(confirmed, probable, new ArrayList<>(meta.values()));
        }

        Queue<PairEval> toNeural = new ConcurrentLinkedQueue<>();

        List<IndexPair> pairList = new ArrayList<>(pairs);
        final int batchSize = 500;
        int batches = (pairList.size() + batchSize - 1) / batchSize;
        CountDownLatch latch = new CountDownLatch(batches);

        AtomicInteger hardRejected = new AtomicInteger();
        AtomicInteger fastRejected = new AtomicInteger();
        AtomicInteger fastConfirmed = new AtomicInteger();

        for (int b = 0; b < batches; b++) {
            int from = b * batchSize;
            int to = Math.min(from + batchSize, pairList.size());
            List<IndexPair> slice = pairList.subList(from, to);

            Runnable job = () -> {
                try {
                    for (IndexPair p : slice) {
                        PairEval ev = evaluatePair(p, restRows);
                        String l = restRows.get(p.first()).value();
                        String r = restRows.get(p.second()).value();

                        double hardThr = (ev.maxLen < 15)
                                ? props.hardRejectShort()
                                : props.hardRejectLong();

                        if (ev.weightedScore <= hardThr) {
                            hardRejected.incrementAndGet();
                            continue;
                        }

                        double confirmThr = (ev.maxLen < props.shortLenLimit())
                                ? props.fastConfirmShort()
                                : props.fastConfirmLong();

                        if (ev.weightedScore >= confirmThr) {
                            String lClean = l.replaceAll("\\d+", "");
                            String rClean = r.replaceAll("\\d+", "");
                            if (countCommonAlpha(lClean, rClean) < 3) {
                                fastRejected.incrementAndGet();
                                continue;
                            }
                            probable.add(mapOriginal(p, restIdx));
                            fastConfirmed.incrementAndGet();
                            updateMeta(meta, p, restIdx);
                        } else if (ev.weightedScore > props.fastRejectThreshold()) {
                            toNeural.add(ev);
                            fastRejected.incrementAndGet();
                        } else {
                            hardRejected.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            };

            workers.execute(job);
        }

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.info("[fuzzy] stage=heuristic hardRejected={} fastRejected={} fastConfirmed={} toNeural={}",
                hardRejected.get(), fastRejected.get(), fastConfirmed.get(), toNeural.size());

        if (!toNeural.isEmpty()) {
            List<NeuralGateService.EvalItem> items = toNeural.stream()
                    .map(e -> new NeuralGateService.EvalItem(e.pair, e.weightedScore, e.maxLen))
                    .toList();

            NeuralGateService.Split split = neuralGate.splitForNeural(items);

            log.info("[fuzzy] gate selectedForNeural={} overflowHighScore={}",
                    split.selectedForNeural().size(), split.overflowHighScore().size());

            for (NeuralGateService.EvalItem it : split.overflowHighScore()) {
                probable.add(mapOriginal(it.pair(), restIdx));
                updateMeta(meta, it.pair(), restIdx);
            }

            List<PairEval> gated = split.selectedForNeural().stream()
                    .map(it -> new PairEval(it.pair(), it.weightedScore(), it.maxLen()))
                    .toList();

            if (!gated.isEmpty()) {
                log.info("[fuzzy] stage=neural batch={}", gated.size());
                runNeuralStage(gated, restRows, restIdx, probable, meta);
            }
        }

        DuplicateMatchResponse result = new DuplicateMatchResponse(confirmed, probable, new ArrayList<>(meta.values()));

        log.info("[fuzzy] output sizes: confirmedPairs={} probablePairs={} metaEntries={}",
                confirmed.size(), probable.size(), result.meta().size());

        return result;
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

        double weighted = props.tokenWeight() * token +
                props.levWeight() * lev +
                props.jwWeight() * jw;

        return new PairEval(pair, weighted, Math.max(left.length(), right.length()));
    }

    private void runNeuralStage(List<PairEval> batch,
                                List<RowNorm> rows,
                                List<Integer> originalIdx,
                                Set<IndexPair> probable,
                                ConcurrentHashMap<Integer, RowMeta> meta) {
        AtomicInteger confirmedByNN = new AtomicInteger();

        for (int i = 0; i < batch.size(); i += props.neuralBatchSize()) {
            int to = Math.min(i + props.neuralBatchSize(), batch.size());
            List<PairEval> slice = batch.subList(i, to);

            Runnable r = () -> {
                List<Pair<String, String>> q = slice.stream()
                        .map(e -> Pair.of(
                                rows.get(e.pair.first()).value(),
                                rows.get(e.pair.second()).value()))
                        .toList();

                Map<Pair<String, String>, Double> scores = neuralService.fetchBatchSimilarityScores(q);

                for (int k = 0; k < slice.size(); k++) {
                    PairEval ev = slice.get(k);

                    double score = scores.getOrDefault(q.get(k), 0.0);
                    double thr = (ev.maxLen < props.nnLenLimit())
                            ? props.nnConfirmShort()
                            : props.nnConfirmLong();

                    if (score >= thr) {
                        probable.add(mapOriginal(ev.pair, originalIdx));
                        confirmedByNN.incrementAndGet();
                        updateMeta(meta, ev.pair, originalIdx);
                    } else {
                        // no-op
                    }
                }
            };

            CompletableFuture.runAsync(r, neuralPool).join();
            log.info("[fuzzy] stage=neural sliceSize={} confirmedByNN={}", slice.size(), confirmedByNN.get());
        }
    }

    private void updateMeta(ConcurrentHashMap<Integer, RowMeta> meta, IndexPair pair, List<Integer> restIdx) {
        int idL = restIdx.get(pair.first());
        int idR = restIdx.get(pair.second());

        RowMeta l = meta.get(idL);
        RowMeta r = meta.get(idR);

        UUID target = (l != null) ? l.clusterId()
                : (r != null) ? r.clusterId()
                : UUID.randomUUID();

        if (l != null && r != null && !l.clusterId().equals(r.clusterId())) {
            UUID obsolete = r.clusterId();
            meta.forEach((idx, m) -> {
                if (m.clusterId().equals(obsolete)) {
                    meta.put(idx, new RowMeta(idx, target, m.kind()));
                }
            });
        }

        RowMeta leftMeta = meta.get(idL);
        RowMeta rightMeta = meta.get(idR);

        if (leftMeta == null) {
            meta.put(idL, new RowMeta(idL, target, ClusterKind.FUZZY));
        }

        if (rightMeta == null) {
            meta.put(idR, new RowMeta(idR, target, ClusterKind.FUZZY));
        }
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    private static int countCommonAlpha(String a, String b) {
        Set<String> ngrams = new HashSet<>();
        String sa = a.toLowerCase().replaceAll("[^a-z]", "");
        String sb = b.toLowerCase().replaceAll("[^a-z]", "");

        for (int len : new int[]{3, 4}) {
            for (int i = 0; i <= sa.length() - len; i++)
                ngrams.add(sa.substring(i, i + len));
        }

        int common = 0;
        for (int len : new int[]{3, 4}) {
            for (int i = 0; i <= sb.length() - len && common < 3; i++)
                if (ngrams.contains(sb.substring(i, i + len)))
                    common++;
        }
        return common;
    }

    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
