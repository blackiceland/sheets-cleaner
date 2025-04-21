package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class NeuralSimilarityServiceImpl implements NeuralSimilarityService {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final double FALLBACK_SCORE = 0.0;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI apiUri;


    public double fetchSimilarityScore(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return FALLBACK_SCORE;
        }

        String cleanLeft = left.replace("|", "").trim();
        String cleanRight = right.replace("|", "").trim();

        try {
            String body = objectMapper.writeValueAsString(Map.of("left", cleanLeft, "right", cleanRight));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(apiUri)
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return FALLBACK_SCORE;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode score = root.get("score");

            if (score != null && score.isNumber()) {
                double value = score.asDouble();
                return (value >= 0.0 && value <= 1.0) ? value : FALLBACK_SCORE;
            }
        } catch (Exception e) {
            return FALLBACK_SCORE;
        }

        return FALLBACK_SCORE;
    }
}


