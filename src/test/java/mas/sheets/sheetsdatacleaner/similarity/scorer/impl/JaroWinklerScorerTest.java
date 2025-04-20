package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
class JaroWinklerScorerTest {

    @Autowired
    private JaroWinklerScorer scorer;

    @ParameterizedTest(name = "{index} => \"{0}\" vs \"{1}\" ≈ {2}")
    @MethodSource("provideTestCases")
    void shouldCalculateExpectedScores(String left, String right, double expectedScore) {
        double actual = scorer.calculateScore(left, right);

        assertThat(actual).isCloseTo(expectedScore, within(0.01));
    }

    private static Stream<Arguments> provideTestCases() {
        return Stream.of(
                Arguments.of("anton markov", "anton markov", 1.0),
                Arguments.of("anton markov", "antonn markov",  0.98),
                Arguments.of("anton markov", "markov anton", 0.58),
                Arguments.of("john doe", "jon do", 0.93),

                Arguments.of("anton.markov@gmail.com", "antonmarkov@gmail.com", 0.99),

                Arguments.of("12345", "12346", 0.0),
                Arguments.of("123.456", "123456", 0.0),
                Arguments.of("001-245-789", "001245789", 0.0),

                Arguments.of("01.01.2023", "2023-01-01", 0.0),
                Arguments.of("2023/01/01", "2023.01.01", 0.0),

                Arguments.of("main street 123", "123 main street", 0.71),
                Arguments.of("ivan petrov", "ivanov petr", 0.92),

                Arguments.of("anton markov | 123 street | 2023", "markov anton | street 123 | 2023", 0.89)
        );
    }
}
