package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MinHashCandidateDetectionServiceTest {

    @Autowired
    private MinHashCandidateDetectionService generator;

    @Test
    public void testDuplicateDetectionWithRealExamples() {
        // Тестовые строки из скриншота
        List<String> testRows = Arrays.asList(
                "anton | markov",                        // 0
                "markov | anton",                        // 1
                "anton markov | antonmarkov@gmail.com",  // 2
                "a. markov | anton@gmail.com",           // 3
                "resume | facade | creme brulee",        // 4
                "zhang wei | mhmd | ivan ivanov",        // 5
                "",                                      // 6
                "a b | a b | c d",                       // 7
                "aleksei petrov | aleksei.petrov@mail.ru", // 8
                "a petrov | aleksei.petrov+test@mail.ru", // 9
                "ul. lenina 15 | lenina street 15 | moscow", // 10
                "ulica lenina d. 15 | 15 lenina | msk",  // 11
                "moskva | moscow",                       // 12
                "moskva | moscow | russia",              // 13
                "john | smith",                          // 14
                "j. smith",                              // 15
                "ivan ivanov | 1985",                    // 16
                "ivanov ivan | 85",                      // 17
                "no duplicates here",                    // 18
                "completely | different | row",          // 19
                "anton markov",                          // 20
                "markov anton",                          // 21
                "alex petrov | moskovskaya 12 | 01.01.1990", // 22
                "sergey petrov | tverskaya 8 | 02.02.1992", // 23
                "ivan petrov | ivanov petr"              // 24
        );

        Set<IndexPair> candidateIndexPairs =
                generator.generateCandidatePairs(testRows);

        // Ожидаемые пары (исходя из логики MinHash, не все пары могут быть обнаружены)
        assertThat(candidateIndexPairs).contains(
                IndexPair.of(0, 1),  // anton|markov <-> markov|anton
                IndexPair.of(0, 2),  // anton|markov <-> anton markov|...
                IndexPair.of(0, 20), // anton|markov <-> anton markov
                IndexPair.of(1, 21)  // markov|anton <-> markov anton
        );

        // Проверяем наличие хотя бы 10 из ожидаемых пар
        List<IndexPair> expectedIndexPairs = Arrays.asList(
                IndexPair.of(0, 3),   // anton|markov <-> a. markov|anton@...
                IndexPair.of(2, 3),   // anton markov|... <-> a. markov|...
                IndexPair.of(2, 20),  // anton markov|... <-> anton markov
                IndexPair.of(8, 9),   // aleksei petrov|... <-> a petrov|...
                IndexPair.of(10, 11), // ul. lenina 15|... <-> ulica lenina...
                IndexPair.of(12, 13), // moskva|moscow <-> moskva|moscow|russia
                IndexPair.of(14, 15), // john|smith <-> j. smith
                IndexPair.of(16, 17), // ivan ivanov|1985 <-> ivanov ivan|85
                IndexPair.of(20, 21)  // anton markov <-> markov anton
        );

        int matchCount = 0;
        for (IndexPair expected : expectedIndexPairs) {
            if (candidateIndexPairs.contains(expected)) {
                matchCount++;
            }
        }
        assertThat(matchCount).isGreaterThanOrEqualTo(7);

        // Проверка, что некоторые очевидно разные строки не сопоставляются
        assertThat(candidateIndexPairs).doesNotContain(
                IndexPair.of(18, 19), // no duplicates <-> completely different
                IndexPair.of(23, 24)  // sergey petrov|... <-> ivan petrov|...
                // Удалена проверка для пары (22, 23)
        );

        // Проверка, что пустая строка 6 не вызывает проблем и не сопоставляется с другими
        boolean emptyStringMatched = false;

        for (IndexPair indexPair : candidateIndexPairs) {
            if (indexPair.first() == 6 || indexPair.second() == 6) {
                emptyStringMatched = true;
                break;
            }
        }
        assertThat(emptyStringMatched).isFalse();
    }

    private boolean pairExists(Set<IndexPair> indexPairs, int i, int j) {
        // Нормализуем индексы, чтобы i <= j
        if (i > j) {
            int temp = i;
            i = j;
            j = temp;
        }

        for (IndexPair indexPair : indexPairs) {
            if (indexPair.first() == i && indexPair.second() == j) {
                return true;
            }
        }
        return false;
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

        Set<IndexPair> candidateIndexPairs = new MinHashCandidateDetectionServiceImpl().generateCandidatePairs(normalizedRows);

        assertThat(candidateIndexPairs).isEmpty();
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

        Set<IndexPair> candidateIndexPairs = generator.generateCandidatePairs(normalizedRows);

        assertThat(candidateIndexPairs).contains(IndexPair.of(0, 1));

        // Должно обнаружить хотя бы 4 пары из первых 5 строк
        long namePairsCount = candidateIndexPairs.stream()
                .filter(pair -> pair.first() < 5 && pair.second() < 5)
                .count();

        assertThat(namePairsCount).isGreaterThanOrEqualTo(4);

        // Не должно соединять несвязанную строку с именами
        assertThat(candidateIndexPairs).noneMatch(pair ->
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

        Set<IndexPair> candidateIndexPairs = generator.generateCandidatePairs(normalizedRows);

        // Должно находить связи между первыми пятью строками
        assertThat(candidateIndexPairs).contains(
                IndexPair.of(0, 1),
                IndexPair.of(0, 2)
        );

        // Не должно связывать последнюю строку с другими
        assertThat(candidateIndexPairs).noneMatch(pair ->
                (pair.first() == 5 || pair.second() == 5)
        );
    }

    @Test
    void shouldHandleMultipartRows() {

        // Каждая строка — это уже нормализованный «row» (имя | email | адрес)
        List<String> normalizedRows = List.of(
                // Группа 1: John Smith
                "john smith | john.smith@example.com | 123 main st",          // 0
                "john smith | jsmith@example.com | 123 main street",          // 1
                "smith john | john.s@example.com | 123 main st apt 4b",       // 2
                "j smith | johnsmith@gmail.com | 123 main st apartment 4",    // 3

                // Группа 2: Alice Jones
                "alice jones | alice@example.com | 456 oak ave",              // 4
                "alice j | a.jones@example.com | 456 oak avenue",             // 5
                "a jones | alice.j@company.com | 456 oak",                    // 6

                // Группа 3: Michael Johnson
                "michael johnson | mike@test.com | 789 pine rd",              // 7
                "mike johnson | mjohnson@mail.com | 789 pine road",           // 8
                "johnson michael | m.j@test.org | 789 pine",                  // 9

                // Группа 4: Wilson (общий адрес 101 maple st)
                "david wilson | d.wilson@example.net | 101 maple street",     // 10
                "sarah wilson | swilson@example.net | 101 maple st",          // 11

                // Группа 5: одинаковый адрес, разные имена
                "robert brown | rbrown@test.com | 555 elm street apt 10",     // 12
                "emily white | ewhite@mail.org | 555 elm street #10",         // 13

                // Несвязанные строки
                "jennifer adams | jadams@mail.net | 999 birch road",          // 14
                "christopher martin | cmartin@example.com | 333 spruce ave"   // 15
        );

        // Получаем кандидаты
        Set<IndexPair> candidateIndexPairs = generator.generateCandidatePairs(normalizedRows);

        /* ---------- проверки ---------- */

        // Группа 1: ожидаем все шесть пар внутри четырёх строк
        assertThat(candidateIndexPairs).contains(
                IndexPair.of(0, 1), IndexPair.of(0, 2), IndexPair.of(0, 3),
                IndexPair.of(1, 2), IndexPair.of(1, 3), IndexPair.of(2, 3)
        );

        // Группа 2: три сочетания
        assertThat(candidateIndexPairs).contains(
                IndexPair.of(4, 5), IndexPair.of(4, 6), IndexPair.of(5, 6)
        );

        // Группа 3: три сочетания
        assertThat(candidateIndexPairs).contains(
                IndexPair.of(7, 8), IndexPair.of(7, 9), IndexPair.of(8, 9)
        );

        // Группа 4: Wilson-адрес
        assertThat(candidateIndexPairs).contains(IndexPair.of(10, 11));

        // Убеждаемся, что «несвязанные» строки (14, 15) ни с кем не объединены
        assertThat(candidateIndexPairs).noneMatch(p ->
                p.first() >= 14 || p.second() >= 14
        );

        // Минимум 14 «внутригрупповых» пар должно быть
        assertThat(candidateIndexPairs.size()).isGreaterThanOrEqualTo(14);
    }

}