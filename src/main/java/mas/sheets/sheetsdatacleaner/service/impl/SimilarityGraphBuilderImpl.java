package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import mas.sheets.sheetsdatacleaner.service.SimilarityGraphBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class SimilarityGraphBuilderImpl implements SimilarityGraphBuilder {

    private final NeuralSimilarityService neuralSimilarityService;

    private static final double DEFAULT_SCORE_THRESHOLD = 0.9;

    public List<List<Integer>> buildSimilarityGraphs(List<String> mergedRows) {
        int size = mergedRows.size();

        List<Queue<Integer>> adjacencyQueues = IntStream.range(0, size)
                .mapToObj(i -> new ConcurrentLinkedQueue<Integer>())
                .collect(Collectors.toCollection(() -> new ArrayList<>(size)));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                int leftIndex = i;

                futures.add(executor.submit(() -> {
                    String leftText = mergedRows.get(leftIndex);

                    for (int j = leftIndex + 1; j < size; j++) {
                        double score = neuralSimilarityService.fetchSimilarityScore(leftText, mergedRows.get(j));

                        if (score >= DEFAULT_SCORE_THRESHOLD) {
                            adjacencyQueues.get(leftIndex).add(j);
                            adjacencyQueues.get(j).add(leftIndex);
                        }
                    }

                    return null;
                }));
            }

            for (Future<Void> future : futures) {
                waitForFutureCompletion(future);
            }
        }

        return adjacencyQueues.stream()
                .map(ArrayList::new)
                .collect(Collectors.toList());
    }

    private void waitForFutureCompletion(Future<Void> future) {
        try {
            future.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Similarity computation was interrupted", ie);
        } catch (ExecutionException ee) {
            throw new RuntimeException("Error during similarity computation", ee.getCause());
        }
    }

}
