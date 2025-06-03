package mas.sheets.sheetsdatacleaner.service.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.properties.DuplicateDetectorProps;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.exception.TooManyRequestsException;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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

        ExactDetectionResult exact = exactDetector.detect(normalized);
        log.info("stage=exact duplicates={} remaining={}",
                exact.duplicateGroups().stream().mapToInt(List::size).sum(),
                exact.remainingRows().size());

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
        log.info("stage=candidate pairs={}", pairs.size());

        if (pairs.isEmpty())
            return new DuplicateMatchResponse(confirmed, probable);

        List<PairEval> toNeural = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(pairs.size());

        AtomicInteger hardRejected = new AtomicInteger();
        AtomicInteger fastRejected = new AtomicInteger();
        AtomicInteger fastConfirmed = new AtomicInteger();

        for (IndexPair p : pairs) {
            Runnable job = () -> {
                try {
                    PairEval ev = evaluatePair(p, restRows);

                    double hardThr = (ev.maxLen < 15)
                            ? props.hardRejectShort()
                            : props.hardRejectLong();

                    if (ev.weightedScore <= hardThr) {
                        hardRejected.incrementAndGet();
                        return;
                    }

                    double confirmThr = (ev.maxLen < props.shortLenLimit())
                            ? props.fastConfirmShort()
                            : props.fastConfirmLong();

                    if (ev.weightedScore >= confirmThr) {
                        probable.add(mapOriginal(p, restIdx));
                        fastConfirmed.incrementAndGet();
                    } else if (ev.weightedScore > props.fastRejectThreshold()) {
                        toNeural.add(ev);
                        fastRejected.incrementAndGet();
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

        if (!toNeural.isEmpty())
            runNeuralStage(toNeural, restRows, restIdx, probable);

        log.info("stage=finish confirmed={} probable={}", confirmed.size(), probable.size());

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

        double weighted = props.tokenWeight() * token
                + props.levWeight() * lev
                + props.jwWeight() * jw;

        return new PairEval(pair, weighted, Math.max(left.length(), right.length()));
    }

    private void runNeuralStage(List<PairEval> batch,
                                List<RowNorm> rows,
                                List<Integer> originalIdx,
                                Set<IndexPair> probable) {
        AtomicInteger confirmedByNN = new AtomicInteger();

        for (int i = 0; i < batch.size(); i += props.neuralBatchSize()) {
            int to = Math.min(i + props.neuralBatchSize(), batch.size());
            List<PairEval> slice = batch.subList(i, to);

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
                    double thr = (ev.maxLen < props.nnLenLimit())
                            ? props.nnConfirmShort()
                            : props.nnConfirmLong();

                    if (score >= thr) {
                        probable.add(mapOriginal(ev.pair, originalIdx));
                        confirmedByNN.incrementAndGet();
                    }
                }
            };

            CompletableFuture.runAsync(r, neuralPool).join();
        }

        log.info("stage=neural batch={} confirmedByNN={}", batch.size(), confirmedByNN.get());
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
