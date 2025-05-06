package mas.sheets.sheetsdatacleaner.similarity.scorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.client.EmbeddingApiClient;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import mas.sheets.sheetsdatacleaner.service.impl.NeuralSimilarityServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(DisplayName.class)
class NeuralSimilarityServiceTest {

    private static final String CONTAINER_IMAGE = "similarity:0.3.0";
    private static final int CONTAINER_PORT = 5000;

    private GenericContainer<?> container;
    private NeuralSimilarityService service;

    @BeforeAll
    void startContainer() {
        container = new GenericContainer<>(DockerImageName.parse(CONTAINER_IMAGE))
                .withExposedPorts(CONTAINER_PORT)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3));

        container.start();

        String baseUrl = "http://%s:%d".formatted(container.getHost(), container.getMappedPort(CONTAINER_PORT));

        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        URI apiUri = URI.create(baseUrl + "/similarity");
        ExecutorService exec = Executors.newFixedThreadPool(4);

        EmbeddingApiClient client = new EmbeddingApiClient(mapper, http, apiUri, exec);

        service = new NeuralSimilarityServiceImpl(client);
    }

    @AfterAll
    void stopContainer() {
        container.stop();
    }

    @ParameterizedTest(name = "#{index}: \"{0}\" vs \"{1}\" ≈ {2}")
    @MethodSource("cases")
    void similarityIsCloseEnough(String left, String right, double expected) {
        double actual = service.fetchSimilarityScore(left, right);
        assertThat(actual).isCloseTo(expected, within(0.02));
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("anton markov", "anton markov", 0.99),
                Arguments.of("anton markov", "markov anton", 0.90),
                Arguments.of("anton markov | antonmarkov@gmail.com", "markov anton | antonmarkov@gmail.com", 0.96),
                Arguments.of("a markov | anton@gmail.com", "anton markov | antonmarkov@gmail.com", 0.97),
                Arguments.of("résumé | crème brûlée", "resume | creme brulee", 0.38),
                Arguments.of("zhang wei | mhmd | ivan ivanov", "zhang vay | mohamad | ivanov", 0.68),
                Arguments.of("", "anton markov", 0.0),
                Arguments.of("main st 123 moscow", "moskva 123 street", 0.42),
                Arguments.of("aleksei petrov | aleksei.petrov@mail.ru", "a petrov | aleksei.petrov+test@mail.ru", 0.76),
                Arguments.of("12345", "12345", 0.98),
                Arguments.of("12345", "54321", 0.09),
                Arguments.of("anton", "алик", 0.13),
                Arguments.of("ul lenina 15 | lenina street 15 | moscow", "ulica lenina d 15 | 15 lenina | msk", 0.74)
        );
    }
}
