package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashLSHCandidateGeneratorImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MinHashLSHCandidateGeneratorTest {

    @Autowired
    private MinHashLSHCandidateGenerator generator;

    @Test
    void shouldFindSimilarCandidatesInSameBand() {
        List<String> normalizedRows = List.of(
                "anton markov",
                "anton arko",
                "anaonmtrkov",
                "antozn rmarkov",
                "antoomarknv",
                "anton umakov"
        );

        Set<MinHashLSHCandidateGeneratorImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        assertThat(candidatePairs).isNotEmpty();

        boolean containsObviousSimilar = candidatePairs.stream().anyMatch(pair ->
                (pair.first().equals(0) && pair.second() != null && pair.second() < normalizedRows.size())
        );

        assertThat(containsObviousSimilar).isTrue();
    }

    @Test
    void shouldReturnNoCandidatesForCompletelyDifferentStrings() {
        List<String> normalizedRows = List.of(
                "elephant in the room",
                "北京大学招生简章",
                "1234567890!@#$%^",
                "очень странное слово",
                "this.is:completely;different",
                "كلمات لا علاقة لها ببعضها"
        );

        Set<MinHashLSHCandidateGeneratorImpl.IndexPair<Integer, Integer>> candidatePairs = new MinHashLSHCandidateGeneratorImpl()
                .generateCandidatePairs(normalizedRows);

        assertThat(candidatePairs).isEmpty();
    }


}