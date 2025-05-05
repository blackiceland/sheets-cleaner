package mas.sheets.sheetsdatacleaner.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class NeuralSimilarityServiceImpl implements NeuralSimilarityService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final double FALLBACK_SCORE = 0.0;
    private static final int MAX_ATTEMPTS = 3;
    private static final int CACHE_MAX_ENTRIES = 10_000;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI api;

    private final Map<Pair<String, String>, Double> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Pair<String, String>, Double> e) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            });

    @PreDestroy
    public void shutdown() {
    }

    @Override
    public double fetchSimilarityScore(String left, String right) {
        if (left == null || right == null) return FALLBACK_SCORE;
        String l = left.strip();
        String r = right.strip();
        if (l.isEmpty() || r.isEmpty()) return FALLBACK_SCORE;

        Pair<String, String> key = Pair.of(l, r);
        Double cached = cache.get(key);
        if (cached != null) return cached;

        return fetchBatchSimilarityScores(List.of(key)).getOrDefault(key, FALLBACK_SCORE);
    }

    @Override
    public Map<Pair<String, String>, Double> fetchBatchSimilarityScores(List<Pair<String, String>> pairs) {

        Map<Pair<String, String>, Double> result = new HashMap<>();
        if (pairs.isEmpty()) return result;

        List<Map<String, String>> payload = pairs.stream()
                .map(p -> Map.of("left", p.getLeft(), "right", p.getRight()))
                .toList();

        String body;
        try {
            body = mapper.writeValueAsString(Map.of("pairs", payload));
        } catch (Exception ex) {
            log.error("JSON marshal error", ex);
            return fillFallback(pairs);
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(api)
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200)
                    throw new RuntimeException("HTTP " + resp.statusCode());

                List<Double> scores = mapper.readValue(resp.body(), new TypeReference<>() {
                });

                for (int i = 0; i < pairs.size(); i++) {
                    double s = (i < scores.size()) ? scores.get(i) : FALLBACK_SCORE;
                    if (s < 0 || s > 1) s = FALLBACK_SCORE;
                    Pair<String, String> key = pairs.get(i);
                    cache.put(key, s);
                    result.put(key, s);
                }
                return result;

            } catch (Exception ex) {
                if (attempt == MAX_ATTEMPTS - 1)
                    log.warn("Neural batch failed after {} attempts: {}", MAX_ATTEMPTS, ex.getMessage());
                try {
                    Thread.sleep(400L << attempt);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return fillFallback(pairs);
    }

    private Map<Pair<String, String>, Double> fillFallback(List<Pair<String, String>> pairs) {
        Map<Pair<String, String>, Double> m = new HashMap<>();
        pairs.forEach(p -> m.put(p, FALLBACK_SCORE));
        return m;
    }
}
