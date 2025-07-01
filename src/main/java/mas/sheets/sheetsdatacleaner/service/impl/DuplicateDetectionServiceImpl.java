package mas.sheets.sheetsdatacleaner.service.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.properties.DuplicateDetectorProps;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.enums.ClusterKind;
import mas.sheets.sheetsdatacleaner.exception.TooManyRequestsException;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.*;
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
    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;
    private final ThreadPoolTaskExecutor workers;
    private final ExecutorService neuralPool;

    public DuplicateDetectionServiceImpl(
            DuplicateDetectorProps props,
            ExactDuplicateDetector exactDetector,
            MinHashCandidateDetectionService candidateGenerator,
            RowNormalizerService normalizer,
            @Qualifier("heuristicScorers") List<SimilarityScorer> scorers,
            NeuralSimilarityService neuralService,
            @Qualifier("workPool") ThreadPoolTaskExecutor workers) {

        this.props = props;
        this.exactDetector = exactDetector;
        this.candidateGenerator = candidateGenerator;
        this.normalizer = normalizer;
        this.scorers = scorers;
        this.neuralService = neuralService;
        this.workers = workers;

        int core = Runtime.getRuntime().availableProcessors();
        this.neuralPool = Executors.newFixedThreadPool(Math.min(4, core));
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
        log.info("stage=normalize rows={}", normalized.size());
        log.debug("normalize.rows={}", fmtRows(normalized));

        ExactDetectionResult exact = exactDetector.detect(normalized);
        log.info("stage=exact groups={} duplicates={} remaining={}",
                exact.duplicateGroups().size(),
                exact.duplicateGroups().stream().mapToInt(List::size).sum(),
                exact.remainRows().size());
        exact.duplicateGroups().forEach(g ->
                log.debug("exact.group={} rows={}", g, fmtRowsByIdx(normalized, g)));
        log.debug("exact.remain={}", fmtRows(exact.remainRows()));

        Set<IndexPair> confirmed = ConcurrentHashMap.newKeySet();
        Set<IndexPair> probable = ConcurrentHashMap.newKeySet();

        for (List<Integer> g : exact.duplicateGroups())
            for (int i = 0; i < g.size(); i++)
                for (int j = i + 1; j < g.size(); j++)
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));

        if (!confirmed.isEmpty())
            log.debug("confirmed.exactPairs={}", fmtPairs(confirmed, normalized));

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

        Set<IndexPair> pairs = candidateGenerator.generateCandidatePairs(restRows);
        log.info("stage=candidate pairs={}", pairs.size());
        log.debug("candidates.pairs={}", fmtPairs(pairs, restRows));

        if (pairs.isEmpty())
            return new DuplicateMatchResponse(confirmed, probable, new ArrayList<>(meta.values()));

        List<PairEval> toNeural = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(pairs.size());

        AtomicInteger hardRejected = new AtomicInteger();
        AtomicInteger fastRejected = new AtomicInteger();
        AtomicInteger fastConfirmed = new AtomicInteger();

        for (IndexPair p : pairs) {
            Runnable job = () -> {
                try {
                    PairEval ev = evaluatePair(p, restRows);
                    String l = restRows.get(p.first()).value();
                    String r = restRows.get(p.second()).value();

                    double hardThr = (ev.maxLen < 15)
                            ? props.hardRejectShort()
                            : props.hardRejectLong();

                    if (ev.weightedScore <= hardThr) {
                        hardRejected.incrementAndGet();
                        log.debug("decision=hardReject pair={} score={} left='{}' right='{}'",
                                p, ev.weightedScore, l, r);
                        return;
                    }

                    double confirmThr = (ev.maxLen < props.shortLenLimit())
                            ? props.fastConfirmShort()
                            : props.fastConfirmLong();

                    if (ev.weightedScore >= confirmThr) {
                        probable.add(mapOriginal(p, restIdx));
                        fastConfirmed.incrementAndGet();
                        updateMeta(meta, p, restIdx);
                        log.debug("decision=fastConfirm pair={} score={} left='{}' right='{}'",
                                p, ev.weightedScore, l, r);
                    } else if (ev.weightedScore > props.fastRejectThreshold()) {
                        toNeural.add(ev);
                        fastRejected.incrementAndGet();
                        log.debug("decision=toNeural pair={} score={} left='{}' right='{}'",
                                p, ev.weightedScore, l, r);
                    } else {
                        hardRejected.incrementAndGet();
                        log.debug("decision=fastReject pair={} score={} left='{}' right='{}'",
                                p, ev.weightedScore, l, r);
                    }
                } finally {
                    latch.countDown();
                }
            };

            try {
                workers.execute(job);
            } catch (RejectedExecutionException ex) {
                throw new TooManyRequestsException();
            }
        }

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        log.info("stage=heuristic hardRejected={} fastRejected={} fastConfirmed={} toNeural={}",
                hardRejected.get(), fastRejected.get(), fastConfirmed.get(), toNeural.size());
        log.debug("heuristic.fastConfirmed={}", fmtPairs(probable, normalized));

        if (!toNeural.isEmpty())
            runNeuralStage(toNeural, restRows, restIdx, probable, meta);

        log.info("stage=finish confirmed={} probable={} meta={}",
                confirmed.size(), probable.size(), meta.size());
        log.debug("finish.confirmedPairs={}", fmtPairs(confirmed, normalized));
        log.debug("finish.probablePairs={}", fmtPairs(probable, normalized));
        log.debug("finish.meta={}", meta.values());

        return new DuplicateMatchResponse(confirmed, probable, new ArrayList<>(meta.values()));
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

        log.debug("score {} «{}» / «{}» token={} lev={} jw={} weighted={}",
                pair, left, right, token, lev, jw, weighted);

        return new PairEval(pair, weighted, Math.max(left.length(), right.length()));
    }

    private void runNeuralStage(List<PairEval> batch,
                                List<RowNorm> rows,
                                List<Integer> originalIdx,
                                Set<IndexPair> probable,
                                ConcurrentHashMap<Integer, RowMeta> meta) {

        log.debug("neural.input.size={} first10={}",
                batch.size(), batch.stream().limit(10)
                        .map(e -> pr(e.pair, rows)).toList());

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

                Map<Pair<String, String>, Double> scores =
                        neuralService.fetchBatchSimilarityScores(q);

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
                        log.debug("decision=nnConfirm pair={} nnScore={} left='{}' right='{}'",
                                ev.pair, score,
                                rows.get(ev.pair.first()).value(),
                                rows.get(ev.pair.second()).value());
                    } else {
                        log.debug("decision=nnReject pair={} nnScore={} left='{}' right='{}'",
                                ev.pair, score,
                                rows.get(ev.pair.first()).value(),
                                rows.get(ev.pair.second()).value());
                    }
                }
            };
            CompletableFuture.runAsync(r, neuralPool).join();
        }

        log.info("stage=neural batch={} confirmedByNN={}", batch.size(), confirmedByNN.get());
    }

    private void updateMeta(ConcurrentHashMap<Integer, RowMeta> meta,
                            IndexPair pair,
                            List<Integer> restIdx) {

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

        if (l == null)
            meta.put(idL, new RowMeta(idL, target, ClusterKind.FUZZY));

        if (r == null || !r.clusterId().equals(target))
            meta.put(idR, new RowMeta(idR, target, ClusterKind.FUZZY));
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    private static Map<Integer, RowNorm> toMap(List<RowNorm> rows) {
        return rows.stream().collect(Collectors.toMap(RowNorm::idx, r -> r));
    }

    private static String fmtRows(List<RowNorm> rows) {
        return rows.stream()
                .map(r -> "[" + r.idx() + "] «" + r.value() + "»")
                .toList()
                .toString();
    }

    private static String fmtRowsByIdx(List<RowNorm> all, List<Integer> idxs) {
        Map<Integer, RowNorm> map = toMap(all);
        return idxs.stream()
                .map(map::get)
                .filter(Objects::nonNull)
                .map(r -> "[" + r.idx() + "] «" + r.value() + "»")
                .toList()
                .toString();
    }

    private static String fmtPairs(Collection<IndexPair> pairs, List<RowNorm> rows) {
        Map<Integer, RowNorm> map = toMap(rows);
        return pairs.stream().map(p -> pr(p, map)).toList().toString();
    }

    private static String pr(IndexPair p, List<RowNorm> rows) {
        Map<Integer, RowNorm> map = toMap(rows);
        return pr(p, map);
    }

    private static String pr(IndexPair p, Map<Integer, RowNorm> map) {
        RowNorm l = map.get(p.first());
        RowNorm r = map.get(p.second());
        String lv = (l != null) ? l.value() : "∅";
        String rv = (r != null) ? r.value() : "∅";
        return p + " -> «" + lv + "» / «" + rv + "»";
    }

    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
