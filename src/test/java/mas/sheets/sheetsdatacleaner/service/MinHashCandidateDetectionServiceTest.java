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
                // Группа 1: John Smith с вариациями имени и адреса
                "john smith | john.smith@example.com | 123 main st",
                "john smith | jsmith@example.com | 123 main street",
                "smith john | john.s@example.com | 123 main st apt 4b",
                "j smith | johnsmith@gmail.com | 123 main st apartment 4",

                // Группа 2: Alice Jones с вариациями
                "alice jones | alice@example.com | 456 oak ave",
                "alice j | a.jones@example.com | 456 oak avenue",
                "a jones | alice.j@company.com | 456 oak",

                // Группа 3: Michael Johnson с вариациями
                "michael johnson | mike@test.com | 789 pine rd",
                "mike johnson | mjohnson@mail.com | 789 pine road",
                "johnson michael | m.j@test.org | 789 pine",

                // Группа 4: Смешанные имена и адреса
                "david wilson | d.wilson@example.net | 101 maple street",
                "james wilson | jwilson@example.net | 202 maple avenue",
                "sarah wilson | swilson@example.net | 101 maple st",

                // Группа 5: Одинаковые адреса, разные имена
                "robert brown | rbrown@test.com | 555 elm street apt 10",
                "emily white | ewhite@mail.org | 555 elm street #10",

                // Группа 6: Похожие электронные адреса
                "thomas lee | t.lee@company.org | 777 cedar lane",
                "timothy lee | timlee@company.org | 888 oak drive",

                // Одиночные строки (не должны образовывать пары)
                "jennifer adams | jadams@mail.net | 999 birch road",
                "christopher martin | cmartin@example.com | 333 spruce avenue"
        );

        Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> candidatePairs = generator.generateCandidatePairs(normalizedRows);

        // Проверка группы 1: John Smith
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 1),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 2),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(0, 3),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(1, 2),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(1, 3),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(2, 3)
        );

        // Проверка группы 2: Alice Jones
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(4, 5),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(4, 6),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(5, 6)
        );

        // Проверка группы 3: Michael Johnson
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(7, 8),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(7, 9),
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(8, 9)
        );

        // Проверка группы 4: Wilson с общими адресами
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(10, 12)  // David Wilson и Sarah Wilson (общий адрес)
        );

        // Проверка группы 5: Общий адрес разных людей
        assertThat(candidatePairs).contains(
                MinHashCandidateDetectionServiceImpl.IndexPair.ofNormalized(13, 14)
        );

        // Проверка случаев, которые не должны быть связаны
        // Jennifer Adams не должна быть связана ни с кем
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 19 || pair.second() == 19)
        );

        // Christopher Martin не должен быть связан ни с кем
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() == 20 || pair.second() == 20)
        );

        // Timothy Lee и Thomas Lee могут быть связаны или нет в зависимости от настроек MinHash
        // Поэтому не проверяем их жестко

        // Проверка общего количества пар
        // Учитывая пороги и параметры MinHash, количество пар может варьироваться
        // Но для 5 групп связанных записей минимальное количество пар должно быть:
        // 6 (Группа 1) + 3 (Группа 2) + 3 (Группа 3) + 1 (Группа 4) + 1 (Группа 5) = 14
        assertThat(candidatePairs.size()).isGreaterThanOrEqualTo(14);

        // Проверка отсутствия связей между группами
        // Группа 1 не должна быть связана с группой 2
        assertThat(candidatePairs).noneMatch(pair ->
                (pair.first() <= 3 && pair.second() >= 4 && pair.second() <= 6) ||
                        (pair.first() >= 4 && pair.first() <= 6 && pair.second() <= 3)
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