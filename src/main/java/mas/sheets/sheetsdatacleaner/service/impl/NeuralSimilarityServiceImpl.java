package mas.sheets.sheetsdatacleaner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class NeuralSimilarityServiceImpl implements NeuralSimilarityService {

    private final ObjectMapper objectMapper;

    private static final String SIMILARITY_API_URL = "http://localhost:5000/similarity";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, Double> similarityCache = new ConcurrentHashMap<>();


    public double fetchSimilarityScore(String firstText, String secondText) {
        String cacheKey = generateCacheKey(firstText, secondText);

        if (similarityCache.containsKey(cacheKey)) {
            return similarityCache.get(cacheKey);
        }

        try {
            String requestBody = buildJsonBody(firstText, secondText);
            HttpRequest similarityRequest = HttpRequest.newBuilder()
                    .uri(URI.create(SIMILARITY_API_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> similarityResponse = httpClient.send(similarityRequest, HttpResponse.BodyHandlers.ofString());

            if (similarityResponse.statusCode() == 200) {
                double score = extractScore(similarityResponse.body());
                similarityCache.put(cacheKey, score);
                return score;
            } else {
                log.warn("Received non-200 response from similarity service: {}", similarityResponse.statusCode());
                return 0.0;
            }
        } catch (Exception e) {
            log.error("Error calling neural similarity service", e);
            return 0.0;
        }
    }

    private String buildJsonBody(String text1, String text2) {
        try {
            return objectMapper.writeValueAsString(Map.of("text1", text1, "text2", text2));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private double extractScore(String jsonResponse) {
        try {
            Map<String, Double> response = objectMapper.readValue(jsonResponse, new TypeReference<>() {});
            return response.getOrDefault("score", 0.0);
        } catch (Exception e) {
            log.warn("Failed to parse similarity score from response: {}", jsonResponse, e);
            return 0.0;
        }
    }

    private String generateCacheKey(String a, String b) {
        return a.compareTo(b) < 0
                ? a + "::" + b
                : b + "::" + a;
    }
}
