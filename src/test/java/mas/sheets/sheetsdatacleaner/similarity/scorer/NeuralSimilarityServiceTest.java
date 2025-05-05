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


@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class NeuralSimilarityServiceTest {

    private static GenericContainer<?> container;
    private NeuralSimilarityServiceImpl scorer;

    private static final String CONTAINER_IMAGE = "similarity:0.2.0";
    private static final int CONTAINER_PORT = 5000;


    @BeforeAll
    void setUp() {
        container = new GenericContainer<>(DockerImageName.parse(CONTAINER_IMAGE))
                .withExposedPorts(CONTAINER_PORT)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(4))
                .withReuse(false);

        container.start();

        String url = String.format(
                "http://%s:%d/similarity",
                container.getHost(),
                container.getMappedPort(5000)
        );

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
        assertThat(actual).isEqualTo(expected);
    }

    private static Stream<Arguments> provideSimilarityCases() {
        return Stream.of(
                Arguments.of("anton markov", "anton markov", 0.989969),
                Arguments.of("anton markov", "markov anton", 0.900221),
                Arguments.of("anton markov | antonmarkov@gmail.com", "anton markov | antonmarkov@gmail.com", 0.962616),
                Arguments.of("anton markov | antonmarkov@gmail.com", "markov anton | antonmarkov@gmail.com", 0.960092),
                Arguments.of("a markov | anton@gmail.com", "anton markov | antonmarkov@gmail.com", 0.940182),
                Arguments.of("résumé | crème brûlée", "resume | creme brulee", 0.386028),
                Arguments.of("zhang wei | mhmd | ivan ivanov", "zhang wei | mhmd | ivan ivanov", 0.9729),
                Arguments.of("zhang wei | mhmd | ivan ivanov", "zhang vay | mohamad | ivanov", 0.714324),
                Arguments.of("", "anton markov", 0.0),
                Arguments.of("main st 123 moscow", "moskva 123 street", 0.418486),
                Arguments.of("aleksei petrov | aleksei.petrov@mail.ru", "a petrov | aleksei.petrov+test@mail.ru", 0.765191),
                Arguments.of("ivan petrov", "ivanov petr", 0.859297),
                Arguments.of("sergey nikolaev", "nikolaev sergei", 0.823801),
                Arguments.of("12345", "12345", 0.980848),
                Arguments.of("12345", "54321", 0.093884),
                Arguments.of("anton", "алик", 0.134055),
                Arguments.of("ul lenina 15 | lenina street 15 | moscow", " ulica lenina d 15 | 15 lenina | msk", 0.77503),

                Arguments.of("alex petrov", "moskovskaya 12", 0.006187),
                Arguments.of("sergey petrov", "tverskaya 8", 0.021578),

                Arguments.of("anton markov", "antonmarkov@gmail.com", 0.751196),
                Arguments.of("a. markov", "anton+dev@gmail.com", 0.169307)

        );
    }
}




