package mas.sheets.sheetsdatacleaner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.JaroWinklerScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DuplicateDetectionServiceTest {

    @ParameterizedTest
    @MethodSource("provideTestRows")
    void shouldDetectDuplicateGroups(List<List<String>> rows) {
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("sentence-scorer:latest"))
                .withExposedPorts(5000)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(90))) {

            container.start();

            String baseUrl = "http://" + container.getHost() + ":" + container.getMappedPort(5000) + "/similarity";

            var request = new DuplicateMatchRequest(
                    "A1:Z999",
                    List.of("Col1", "Col2", "Col3"),
                    rows,
                    "TestSheet",
                    "test-spreadsheet-id",
                    false
            );

            var duplicateService = new DuplicateDetectionServiceImpl(
                    new MinHashCandidateDetectionServiceImpl(),
                    new RowNormalizerServiceImpl(),
                    List.of(
                            new TokenSetRatioScorer(),
                            new LevenshteinScorer(),
                            new JaroWinklerScorer()
                    ),
                    new NeuralSimilarityServiceImpl(new ObjectMapper(), HttpClient.newHttpClient(), URI.create(baseUrl))
            );

            DuplicateMatchResponse response = duplicateService.findDuplicates(request);

            assertThat(response).isNotNull();
            assertThat(response.highConfidenceGroups()).isNotEmpty();
        }
    }

    private static Stream<List<List<String>>> provideTestRows() {
        return Stream.of(
                List.of(
                        List.of("anton", "markov", ""),
                        List.of("markov", "anton", ""),
                        List.of("anton markov", "antonmarkov@gmail.com", ""),
                        List.of("a. markov", "anton+dev@gmail.com", ""),
                        List.of("résumé", "façade", "crème brûlée"),
                        List.of("张伟", "mhmd", "Иван Иванов"),
                        List.of("", "", ""),
                        List.of("a b", "a b", "c d"),
                        List.of("aleksei petrov", "aleksei.petrov@mail.ru", ""),
                        List.of("a petrov", "aleksei.petrov+test@mail.ru", ""),
                        List.of("ул. Ленина, 15", "lenina street 15", "moscow"),
                        List.of("улица Ленина, д. 15", "15 lenina", "msk"),
                        List.of("Москва", "Moscow", ""),
                        List.of("2024-01-01", "01.01.2024", ""),
                        List.of("john", "smith", ""),
                        List.of("j. smith", "", ""),
                        List.of("ivan ivanov", "", "1985"),
                        List.of("ivanov ivan", "", "85"),
                        List.of("", "", "no duplicates here"),
                        List.of("completely", "different", "row")
                )
        );
    }
}
