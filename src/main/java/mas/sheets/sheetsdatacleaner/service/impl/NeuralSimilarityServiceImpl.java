package mas.sheets.sheetsdatacleaner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NeuralSimilarityServiceImpl implements NeuralSimilarityService {

    private static final String SIMILARITY_API_URL = "http://localhost:5000/similarity";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final Cache<String, Double> similarityCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();

    @Override
    public double fetchSimilarityScore(String text1, String text2) {
        String cacheKey = makeCacheKey(text1, text2);
        Double cachedScore = similarityCache.get(cacheKey, key -> computeSimilarity(text1, text2));

        return cachedScore != null ? cachedScore : 0.0;
    }

    private double computeSimilarity(String firstText, String secondText) {
        String requestBody;

        try {
            requestBody = objectMapper.writeValueAsString(Map.of(
                    "text1", firstText,
                    "text2", secondText
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize request body for texts '{}' and '{}'", firstText, secondText, e);
            return 0.0;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SIMILARITY_API_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            log.error("Error sending HTTP request to similarity service", e);
            Thread.currentThread().interrupt();
            return 0.0;
        }

        if (response.statusCode() != 200) {
            log.warn("Similarity service returned status {} with body: {}",
                    response.statusCode(), response.body());
            return 0.0;
        }

        return parseScore(response.body());
    }

    private double parseScore(String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse similarity service response as JSON: {}", responseBody, e);
            return 0.0;
        }

        JsonNode scoreNode = root.get("score");
        if (scoreNode == null || !scoreNode.isNumber()) {
            log.warn("Missing or invalid 'score' in response: {}", responseBody);
            return 0.0;
        }

        double score = scoreNode.asDouble();
        if (score < 0.0 || score > 1.0) {
            log.warn("Parsed similarity score out of range [0,1]: {}", score);
            return 0.0;
        }

        return score;
    }

    private String makeCacheKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "::" + b : b + "::" + a;
    }
}

