package mas.sheets.sheetsdatacleaner.service.impl;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.client.EmbeddingApiClient;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class NeuralSimilarityServiceImpl implements NeuralSimilarityService {

    private static final double FALLBACK_SCORE = 0.0;
    private static final int CACHE_MAX_SIZE = 10_000;

    private final EmbeddingApiClient embeddingClient;

    private final Map<Pair<String, String>, Double> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Pair<String, String>, Double> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

    @PreDestroy
    public void shutdown() {}

    @Override
    public double fetchSimilarityScore(String left, String right) {
        if (left == null || right == null) {
            return FALLBACK_SCORE;
        }

        Pair<String, String> key = Pair.of(left.strip(), right.strip());

        if (key.getLeft().isEmpty() || key.getRight().isEmpty()) {
            return FALLBACK_SCORE;
        }

        Double cached = cache.get(key);

        if (cached != null) {
            return cached;
        }

        return fetchBatchSimilarityScores(List.of(key)).getOrDefault(key, FALLBACK_SCORE);
    }

    @Override
    public Map<Pair<String, String>, Double> fetchBatchSimilarityScores(List<Pair<String, String>> pairs) {
        if (pairs.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Pair<String, String>, Double> result = new HashMap<>();
        List<Pair<String, String>> uncached = new ArrayList<>();

        for (Pair<String, String> p : pairs) {
            Double c = cache.get(p);

            if (c != null) {
                result.put(p, c);
            } else {
                uncached.add(p);
            }
        }

        if (uncached.isEmpty()) {
            return result;
        }

        List<Map<String, String>> payload = uncached.stream()
                .map(p -> Map.of("left", p.getLeft(), "right", p.getRight()))
                .toList();

        List<Double> scores;

        try {
            scores = embeddingClient.embedBatch(payload).join();
        } catch (CompletionException ex) {
            log.warn("Embedding request failed, using fallback scores: {}", ex.getCause().getMessage(), ex.getCause());
            scores = Collections.nCopies(payload.size(), FALLBACK_SCORE);
        }

        for (int i = 0; i < uncached.size(); i++) {
            double s = (i < scores.size()) ? scores.get(i) : FALLBACK_SCORE;
            if (s < 0 || s > 1) {
                s = FALLBACK_SCORE;
            }

            Pair<String, String> k = uncached.get(i);
            cache.put(k, s);
            result.put(k, s);
        }

        return result;
    }
}
