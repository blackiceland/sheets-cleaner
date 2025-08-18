package mas.sheets.sheetsdatacleaner.similarity.scorer;

import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class NeuralSimilarityServiceTest {

    private static final int PORT = 5000;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> similarity =
            new GenericContainer<>("similarity:0.3.2")
                    .withExposedPorts(PORT)
                    .waitingFor(
                            Wait.forHttp("/health")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(5)));


    static {
        similarity.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("embedding.api.base-url", () -> "http://" + similarity.getHost() + ":" + similarity.getMappedPort(PORT) + "/similarity");
        r.add("resilience4j.timelimiter.instances.embeddingApi.timeoutDuration", () -> "15s");
    }

    @Autowired
    private NeuralSimilarityService service;

    @ParameterizedTest(name = "#{index}: \"{0}\" vs \"{1}\" ≈ {2}")
    @MethodSource("cases")
    void similarityIsCloseEnough(String left, String right, double expected) {
        double actual = service.fetchSimilarityScore(left, right);
        assertThat(actual).isCloseTo(expected, within(0.02));
    }

    private static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("anton markov", "anton markov", 0.93),
                Arguments.of("anton markov", "markov anton", 0.90),
                Arguments.of("anton markov | antonmarkov@gmail.com", "markov anton | antonmarkov@gmail.com", 0.92),
                Arguments.of("a markov | anton@gmail.com", "anton markov | antonmarkov@gmail.com", 0.92),
                Arguments.of("résumé | crème brûlée", "resume | creme brulee", 0.43),
                Arguments.of("zhang wei | mhmd | ivan ivanov", "zhang vay | mohamad | ivanov", 0.70),
                Arguments.of("", "anton markov", 0.0),
                Arguments.of("main st 123 moscow", "moskva 123 street", 0.44),
                Arguments.of("main st 123 moscow", "main st 124 moscow", 0.99),
                Arguments.of("aleksei petrov | aleksei.petrov@mail.ru", "a petrov | aleksei.petrov+test@mail.ru", 0.78),
                Arguments.of("12345", "12345", 0.98),
                Arguments.of("12345", "54321", 0.14),
                Arguments.of("anton", "алик", 0.31),
                Arguments.of("ul lenina 15 | lenina street 15 | moscow", "ulica lenina d 15 | 15 lenina | msk", 0.77)
        );
    }
}
