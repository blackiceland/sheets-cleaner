package mas.sheets.sheetsdatacleaner.service.impl;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.properties.DuplicateDetectorProps;
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

    private final DuplicateDetectorProps props;
    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    private final ExecutorService workers;
    private final ExecutorService neuralPool;

    public DuplicateDetectionServiceImpl(
            DuplicateDetectorProps props,
            ExactDuplicateDetector exactDetector,
            MinHashCandidateDetectionService candidateGenerator,
            RowNormalizerService normalizer,
            @Qualifier("heuristicScorers") List<SimilarityScorer> scorers,
            NeuralSimilarityService neuralService) {

        this.props = props;
        this.exactDetector = exactDetector;
        this.candidateGenerator = candidateGenerator;
        this.normalizer = normalizer;
        this.scorers = scorers;
        this.neuralService = neuralService;

        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(props.maxInFlight());

        int core = Runtime.getRuntime().availableProcessors();
        int pool = Math.max(1, core * props.workerMultiplier());
        this.workers = new ThreadPoolExecutor(pool, pool, 0L, TimeUnit.SECONDS, queue);
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

                    double hardThr = (ev.maxLen < 15)
                            ? props.hardRejectShort()
                            : props.hardRejectLong();

                    if (ev.weightedScore <= hardThr) return;

                    double confirmThr = (ev.maxLen < props.shortLenLimit())
                            ? props.fastConfirmShort()
                            : props.fastConfirmLong();

                    if (ev.weightedScore >= confirmThr) {
                        probable.add(mapOriginal(p, restIdx));
                    } else if (ev.weightedScore > props.fastRejectThreshold()) {
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

        log.info("NN confirmed {} pairs ({} evaluated)", confirmedByNN.get(), batch.size());
    }

    private IndexPair mapOriginal(IndexPair p, List<Integer> idx) {
        return IndexPair.of(idx.get(p.first()), idx.get(p.second()));
    }

    private record PairEval(IndexPair pair, double weightedScore, int maxLen) {
    }
}
