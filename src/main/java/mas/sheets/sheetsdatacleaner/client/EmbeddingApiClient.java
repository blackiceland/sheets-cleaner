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

    @Bulkhead(name = "embeddingApi", type = Bulkhead.Type.SEMAPHORE)
    @TimeLimiter(name = "embeddingApi")
    @CircuitBreaker(name = "embeddingApi", fallbackMethod = "fallback")
    @RateLimiter(name = "embeddingApi")
    public CompletableFuture<List<Double>> embedBatch(List<Map<String, String>> payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String body = mapper.writeValueAsString(payload);

                HttpRequest req = HttpRequest.newBuilder(api)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                String json = http.send(req, HttpResponse.BodyHandlers.ofString()).body();

                return mapper.readValue(json, new TypeReference<>() {
                });

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, embeddingExecutor);
    }

    @SuppressWarnings("unused")
    private CompletableFuture<List<Double>> fallback(List<Map<String, String>> payload, Throwable ex) {
        log.warn("Embedding fallback: {}", ex.getMessage(), ex);

        return CompletableFuture.completedFuture(
                Collections.nCopies(payload.size(), 0.0));
    }
}
