package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.within;

class JaroWinklerScorerTest {

    private JaroWinklerScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new JaroWinklerScorer();
    }

    @Test
    void shouldCorrectlyCalculateScores() {
        Object[][] pairs = {
                {"abc", "abc"},
                {"", ""},
                {null, null},
                {null, "abc"},
                {"abc", null},
                {"", "abc"},
                {"abc", ""},
                {"   ", "abc"},
                {"abc", "  abc  "},
                {"  hello world  ", "hello world"},
                {"abcdefghijklm", "a"},
                {"abcdef", "abc"},
                {"abcd", "abc"},
                {"martha", "marhta"},
                {"dixon", "dickson"},
                {"dice", "rice"},
                {"duane", "dwayne"},
                {"CRATE", "TRACE"},
                {"DWAYNE", "DUANE"},
                {"MARTHA", "MARHTA"},
                {"John Smith", "Smith, John"},
                {"Mike Jones", "Jones Mike"},
                {"James Peterson", "Jamie Peterson"},
                {"123 Main St", "123 Main Street"},
                {"Main St 123", "123 Main St"},
                {"1600 Pennsylvania Ave", "1600 Pennsylvania Avenue"},
                {"john.smith@example.com", "john.s@example.com"},
                {"info@company.org", "contact@company.org"},
                {"123-456-7890", "(123) 456-7890"},
                {"555-1234", "555 1234"},
                {"2023-01-15", "15/01/2023"},
                {"Jan 15, 2023", "January 15th, 2023"},
                {"Microsoft Corp", "Microsoft Corporation"},
                {"IBM", "International Business Machines"},
                {"Dr. John", "Doctor John"},
                {"Dept", "Department"},
                {"AbCdEf", "aBcDeF"},
                {"HELLO", "hello"},
                {"München", "Muenchen"},
                {"Straße", "Strasse"},
                {"user123", "user 123"},
                {"#hashtag", "hashtag"},
                {"ABC-123-XYZ", "ABC 123 XYZ"},
                {"ISBN 978-3-16-148410-0", "ISBN-978-3-16-148410-0"},
                {"Hello, world!", "Hello world!"},
                {"a+b=c", "a + b = c"},
                {"a", "b"},
                {"ab", "ac"},
                {"This is a very long string that should test", "This is a very similar long string that should"},
                {"function test()", "function test() {"},
                {"int main() {}", "void main() {}"}
        };

        for (Object[] p : pairs) {
            String s1 = (String) p[0];
            String s2 = (String) p[1];

            double score = scorer.calculateScore(s1, s2);

            assertThat(score)
                    .as("Score must be in [0,1] for '%s' – '%s'", s1, s2)
                    .isBetween(0.0, 1.0);

            boolean bothEmptyOrNull =
                    (s1 == null || s1.trim().isEmpty()) &&
                            (s2 == null || s2.trim().isEmpty());
            if (bothEmptyOrNull) {
                assertThat(score)
                        .as("Expected ≈1.0 for both empty/null ['%s','%s']", s1, s2)
                        .isCloseTo(1.0, within(0.05));
                continue;
            }

            boolean oneEmptyOrNull =
                    (s1 == null || s1.trim().isEmpty()) ||
                            (s2 == null || s2.trim().isEmpty());
            if (oneEmptyOrNull) {
                assertThat(score)
                        .as("Expected ≈0.0 when one side empty/null ['%s','%s']", s1, s2)
                        .isCloseTo(0.0, within(0.05));
                continue;
            }

            boolean identicalIgnoreCase =
                    s1.trim().equalsIgnoreCase(s2.trim());
            if (identicalIgnoreCase) {
                assertThat(score)
                        .as("Expected ≈1.0 for identical strings ['%s','%s']", s1, s2)
                        .isCloseTo(1.0, within(0.05));
            }
        }
    }
}
