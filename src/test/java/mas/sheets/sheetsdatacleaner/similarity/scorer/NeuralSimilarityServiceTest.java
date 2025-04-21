package mas.sheets.sheetsdatacleaner.similarity.scorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.service.impl.NeuralSimilarityServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class NeuralSimilarityServiceTest {

    private static GenericContainer<?> container;
    private NeuralSimilarityServiceImpl scorer;

    @BeforeAll
    void setUp() {
        container = new GenericContainer<>(DockerImageName.parse("sentence-scorer:latest"))
                .withExposedPorts(5000)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofSeconds(60));

        container.start();

        String url = "http://" + container.getHost() + ":" + container.getMappedPort(5000) + "/similarity";

        scorer = new NeuralSimilarityServiceImpl(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create(url)
        );
    }

    @AfterAll
    void tearDown() {
        container.stop();
    }

    @ParameterizedTest(name = "#{index} – \"{0}\" vs \"{1}\" ≈ {2}")
    @MethodSource("provideSimilarityCases")
    void shouldFetchSimilarityScoreWithinReasonableDelta(String left, String right, double expected) {
        double actual = scorer.fetchSimilarityScore(left, right);
        assertThat(actual).isCloseTo(expected, within(0.15));
    }

    private static Stream<Arguments> provideSimilarityCases() {
        return Stream.of(
                Arguments.of("anton markov", "anton markov", 1.0),
                Arguments.of("anton markov", "markov anton", 0.9),
                Arguments.of("anton markov | antonmarkov@gmail.com", "anton markov | antonmarkov@gmail.com", 1.0),
                Arguments.of("anton markov | antonmarkov@gmail.com", "markov anton | antonmarkov@gmail.com", 0.95),
                Arguments.of("a markov | anton@gmail.com", "anton markov | antonmarkov@gmail.com", 0.8),
                Arguments.of("résumé | crème brûlée", "resume | creme brulee", 0.95),
                Arguments.of("zhang wei | mhmd | ivan ivanov", "zhang wei | mhmd | ivan ivanov", 1.0),
                Arguments.of("zhang wei | mhmd | ivan ivanov", "zhang vay | mohamad | ivanov", 0.8),
                Arguments.of("", "anton markov", 0.0),
                Arguments.of("main st 123 moscow", "moskva 123 street", 0.7),
                Arguments.of("aleksei petrov | aleksei.petrov@mail.ru", "a petrov | aleksei.petrov+test@mail.ru", 0.85),
                Arguments.of("ivan petrov", "ivanov petr", 0.85),
                Arguments.of("sergey nikolaev", "nikolaev sergei", 0.9),
                Arguments.of("12345", "12345", 1.0),
                Arguments.of("12345", "54321", 0.5),
                Arguments.of("anton", "алик", 0.43)
        );
    }
}




