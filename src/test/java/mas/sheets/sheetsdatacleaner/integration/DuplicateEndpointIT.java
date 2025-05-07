package mas.sheets.sheetsdatacleaner.integration;

import mas.sheets.sheetsdatacleaner.SheetsDataCleanerApplication;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = SheetsDataCleanerApplication.class)
class DuplicateEndpointIT {

    @Container
    static GenericContainer<?> similarity =
            new GenericContainer<>("similarity:0.3.0")
                    .withExposedPorts(5000)
                    .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    @DynamicPropertySource
    static void cfg(DynamicPropertyRegistry r) {
        r.add("embedding.api.url",
                () -> "http://" + similarity.getHost()
                        + ":" + similarity.getMappedPort(5000)
                        + "/similarity");
    }

    @Autowired
    DuplicateDetectionService duplicateService;

    @ParameterizedTest
    @MethodSource("rows")
    void ok(List<List<String>> rows) {
        DuplicateMatchResponse resp = duplicateService.findDuplicates(new DuplicateMatchRequest(rows));

        assertThat(resp.confirmed()).isNotEmpty();
    }

    private static Stream<List<List<String>>> rows() {
        return Stream.of(
                List.of(
                        List.of("anton", "markov"),
                        List.of("markov", "anton")
                )
        );
    }
}


