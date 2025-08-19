package mas.sheets.sheetsdatacleaner.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Component
@Slf4j
public class EmbeddingApiClient {

    private final ObjectMapper mapper;

    private final HttpClient http;

    @Qualifier("embeddingApiUri")
    URI api;

    private final ExecutorService embeddingExecutor;

    public EmbeddingApiClient(
            ObjectMapper mapper,
            HttpClient http,
            @Qualifier("embeddingApiUri") URI api,
            ExecutorService executor) {
        this.mapper = mapper;
        this.http = http;
        this.api = api;
        this.embeddingExecutor = executor;
    }

    @Bulkhead(name = "embeddingApi", type = Bulkhead.Type.THREADPOOL)
    @TimeLimiter(name = "embeddingApi")
    @CircuitBreaker(name = "embeddingApi", fallbackMethod = "fallback")
    @RateLimiter(name = "embeddingApi")
    public CompletableFuture<List<Double>> embedBatch(List<Map<String, String>> payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = mapper.writeValueAsString(payload);

                long t0 = System.nanoTime();
                HttpRequest req = HttpRequest.newBuilder(api)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.debug("embedding.api status={} timeMs={} payloadSize={} url={}",
                        resp.statusCode(), ms, payload == null ? 0 : payload.size(), api);

                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("Embedding API " + resp.statusCode() + ": " + resp.body());
                }

                List<Double> scores = mapper.readValue(resp.body(), new TypeReference<>() {});
                if (payload != null && scores != null && scores.size() != payload.size()) {
                    log.warn("embedding.api sizeMismatch sent={} got={}", payload.size(), scores.size());
                }
                if (scores != null && !scores.isEmpty()) {
                    double min = 1.0, max = 0.0; int zeros = 0;
                    for (Double s : scores) {
                        double v = (s == null) ? 0.0 : s;
                        if (v == 0.0) zeros++;
                        if (v < min) min = v;
                        if (v > max) max = v;
                    }
                    log.debug("embedding.api scores size={} zeros={} min={} max={}", scores.size(), zeros, min, max);
                }
                return scores;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, embeddingExecutor);
    }

    @SuppressWarnings("unused")
    private CompletableFuture<List<Double>> fallback(List<Map<String, String>> payload, Throwable ex) {
        log.warn("Embedding fallback: {}", ex.getMessage());
        return CompletableFuture.completedFuture(Collections.nCopies(payload.size(), 0.0));
    }
}
