package mas.sheets.sheetsdatacleaner.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CompositionIT {

    @Container
    private static final DockerComposeContainer<?> ENV =
            new DockerComposeContainer<>(new File("docker-compose.yml"))
                    .withExposedService("cleaner", 8080, Wait.forListeningPort());

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @ParameterizedTest
    @MethodSource("rows")
    void ok(List<List<String>> rows) throws Exception {
        int port = ENV.getServicePort("cleaner", 8080);

        DuplicateMatchRequest req = new DuplicateMatchRequest(rows);
        String body = JSON.writeValueAsString(req);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/sheets/duplicates"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = CLIENT.send(httpReq, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);

        DuplicateMatchResponse parsed = JSON.readValue(resp.body(), DuplicateMatchResponse.class);
        assertThat(parsed.confirmed()).isNotEmpty();
    }

    private static Stream<List<List<String>>> rows() {
        return Stream.of(
                List.of(
                        List.of("Anton", "Markov"),
                        List.of("Markov", "Anton")
                )
        );
    }
}
