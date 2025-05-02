package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MinHashCandidateDetectionServiceTest {

    @Autowired
    private MinHashCandidateDetectionService generator;

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

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

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

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = new MinHashCandidateDetectionServiceImpl()
                .generateCandidatePairs(normalizedRows);

        assertThat(candidatePairs).isEmpty();
    }

    @Test
    void shouldDetectNameVariations() {
        List<String> normalizedRows = List.of(
                "john smith",
                "smith john",
                "j smith",
                "johnny smith",
                "john smyth",
                "completely different"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 1)
        );

        // Должно обнаружить хотя бы 4 пары из первых 5 строк
        long namePairsCount = candidatePairs.stream()
                .filter(pair -> pair.first() < 5 && pair.second() < 5)
                .count();

        assertThat(namePairsCount).isGreaterThanOrEqualTo(4);

        // Не должно соединять несвязанную строку с именами
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 5 || pair.second() == 5)
        );
    }

    @Test
    void shouldDetectPhoneNumberVariations() {
        List<String> normalizedRows = List.of(
                "123 456 7890",
                "123-456-7890",
                "(123) 456-7890",
                "+1 123 456 7890",
                "123.456.7890",
                "987 654 3210"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Должно находить связи между первыми пятью строками
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 1),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 2)
        );

        // Не должно связывать последнюю строку с другими
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 5 || pair.second() == 5)
        );
    }

    @Test
    void shouldDetectAddressVariations() {
        List<String> normalizedRows = List.of(
                "123 main street apt 4b new york ny 10001",
                "123 main st apartment 4b nyc 10001",
                "123 main street #4b new york ny",
                "123 main st suite 4b ny 10001",
                "456 oak avenue chicago il 60007"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Проверяем, что первые 4 адреса обнаружены как похожие
        int mainStreetPairsCount = 0;
        for (MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer> pair : candidatePairs) {
            if (pair.first() < 4 && pair.second() < 4) {
                mainStreetPairsCount++;
            }
        }

        assertThat(mainStreetPairsCount).isGreaterThanOrEqualTo(3);

        // Последний адрес не должен быть связан с другими
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 4 || pair.second() == 4)
        );
    }

    @Test
    void shouldDetectDateVariations() {
        List<String> normalizedRows = List.of(
                "01 01 2023",
                "2023 01 01",
                "01 jan 2023",
                "january 1 2023",
                "01/01/2023",
                "01-01-2023",
                "02 02 2023"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Должно найти связи между первыми шестью строками
        int datePairsCount = 0;
        for (MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer> pair : candidatePairs) {
            if (pair.first() < 6 && pair.second() < 6) {
                datePairsCount++;
            }
        }

        assertThat(datePairsCount).isGreaterThanOrEqualTo(5);

        // Не должно связывать последнюю дату с другими
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 6 || pair.second() == 6)
        );
    }

    @Test
    void shouldDetectCompanyNameVariations() {
        List<String> normalizedRows = List.of(
                "apple inc",
                "apple incorporated",
                "apple",
                "johnson & johnson",
                "johnson and johnson",
                "j&j",
                "microsoft corporation"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Проверяем связи между вариациями названий Apple
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 1),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 2)
        );

        // Проверяем связи между вариациями названий Johnson & Johnson
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(3, 4)
        );

        // Microsoft не должен быть связан с другими названиями
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 6 || pair.second() == 6)
        );
    }

    @Test
    void shouldHandleMultipartRows() {
        List<String> normalizedRows = List.of(
                "john smith | john.smith@example.com | 123 main st",
                "john smith | jsmith@example.com | 123 main street",
                "smith john | john.s@example.com | 123 main st apt 4b",
                "alice jones | alice@example.com | 456 oak ave"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Первые три строки должны быть связаны
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 1),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 2)
        );

        // Последняя строка не должна быть связана с другими
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 3 || pair.second() == 3)
        );
    }

    @Test
    void shouldDetectShortInitialsWithFullNames() {
        List<String> normalizedRows = List.of(
                "dr r feynman",
                "richard feynman",
                "r p feynman",
                "richard phillips feynman",
                "albert einstein"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Должно связать варианты имени Фейнмана
        int feynmanPairsCount = 0;
        for (MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer> pair : candidatePairs) {
            if (pair.first() < 4 && pair.second() < 4) {
                feynmanPairsCount++;
            }
        }

        assertThat(feynmanPairsCount).isGreaterThanOrEqualTo(2);

        // Эйнштейн не должен быть связан с Фейнманом
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 4 || pair.second() == 4)
        );
    }
}