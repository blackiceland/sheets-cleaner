package mas.sheets.sheetsdatacleaner.similarity.scorer;

import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;

import java.util.stream.Stream;

@SpringBootTest
public class TokenSetRatioScorerTest {

    @Autowired
    private TokenSetRatioScorer scorer;

    @ParameterizedTest(name = "[{index}] \"{0}\" vs \"{1}\" → score ≈ {2}")
    @MethodSource("provideTestCases")
    void shouldComputeExpectedTokenSetScore(String left, String right, double expectedScore) {
        double actualScore = scorer.calculateScore(left, right);

        assertThat(actualScore).isCloseTo(expectedScore, within(0.01));
    }

    private static Stream<Arguments> provideTestCases() {
        return Stream.of(
                Arguments.of("anton markov", "anton markov", 1.0),
                Arguments.of("markov anton", "anton markov", 1.0),
                Arguments.of("anton markov", "anton v markov", 0.8),
                Arguments.of("anton markov", "markov ivan", 0.5),
                Arguments.of("anton markov", "elena petrova", 0.0),
                Arguments.of("", "", 0.0),
                Arguments.of("anton", "", 0.0),
                Arguments.of("", "anton", 0.0),
                Arguments.of(null, null, 0.0),
                Arguments.of("anton", null, 0.0),
                Arguments.of(null, "anton", 0.0),
                Arguments.of("  anton   markov  ", "markov anton", 1.0),
                Arguments.of("zhurnal ucheta", "uchetnyi zhurnal", 0.5)
        );
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" vs \"{1}\" → ≈ {2}")
    @MethodSource("realisticTestCases")
    void shouldCorrectlyScoreRealisticExamples(String left, String right, double expectedScore) {
        double actual = scorer.calculateScore(left, right);

        assertThat(actual).isCloseTo(expectedScore, within(0.01));
    }

    private static Stream<Arguments> realisticTestCases() {
        return Stream.of(
                Arguments.of("anton markov | antonmarkov@gmail.com", "markov anton | antonmarkov@gmail.com", 1.0),
                Arguments.of("anton | markov", "markov anton | antonmarkov@gmail.com", 0.8),
                Arguments.of("anton markov | antonmarkov@gmail.com", "anton markov | a.markov@gmail.com", 0.66),
                Arguments.of("a markov | anton@gmail.com", "anton markov | antonmarkov@gmail.com", 0.33),
                Arguments.of("anton markov", "ivan markov", 0.5),
                Arguments.of("progress ooo", "ooo progress", 1.0),
                Arguments.of("progress ooo | contact@progress.ru", "ooo progress | contact@progress.com", 0.66),
                Arguments.of("alex petrov | alex.petrov@mail.ru", "elena petrova | elena.p@gmail.com", 0.0),
                Arguments.of("123 main st moscow", "main st 123 moskva", 0.75),
                Arguments.of("", "anton markov", 0.0),
                Arguments.of("anton petrov | anton.petrov+dev@gmail.com", "anton petrov | anton.petrov@gmail.com", 0.66),
                Arguments.of("creme brulee | facade | resume", "zhang wei | mhmd | ivan ivanov", 0.0)
        );
    }
}

