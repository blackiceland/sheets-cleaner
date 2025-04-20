package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;

@SpringBootTest
class LevenshteinScorerTest {

    @Autowired
    private LevenshteinScorer levenshteinScorer;

    @ParameterizedTest(name = "{index} => \"{0}\" vs \"{1}\" ≈ {2}")
    @MethodSource("provideTestCases")
    void shouldComputeExpectedLevenshteinScore(String left, String right, double expectedScore) {
        double actualScore = levenshteinScorer.calculateScore(left, right);
        assertThat(actualScore).isCloseTo(expectedScore, within(0.01));
    }

    private static Stream<Arguments> provideTestCases() {
        return Stream.of(
                Arguments.of("anton", "anton", 1.0),
                Arguments.of("anton", "antoon", 0.83),
                Arguments.of("anton", "andrey", 0.33),
                Arguments.of("resume", "resumé", 0.83),
                Arguments.of("zhang wei", "zhang way", 0.77),
                Arguments.of("shang way", "zhang way", 0.88),
                Arguments.of("anton markov", "ant0n mark0v", 0.83),
                Arguments.of("anton.markov@gmail.com", "antonmarkov@gmail.com", 0.95),
                Arguments.of("", "", 0.0),
                Arguments.of("a", "", 0.0),
                Arguments.of("", "a", 0.0),
                Arguments.of("abc", "xyz", 0.0)
        );
    }
}
