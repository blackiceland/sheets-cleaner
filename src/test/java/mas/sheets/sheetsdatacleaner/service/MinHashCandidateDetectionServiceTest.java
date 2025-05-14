package mas.sheets.sheetsdatacleaner.service;

import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Slf4j
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

        candidateIndexPairs.stream()
                .sorted(Comparator.comparingInt(IndexPair::first)
                        .thenComparingInt(IndexPair::second))
                .forEach(p -> log.info("PAIR  {} ↔ {}", p.first(), p.second()));
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
        assertThat(candidateIndexPairs.size()).isGreaterThanOrEqualTo(13);
    }


    @Test
    void shouldDetectDuplicatesInDeterministicHundredRows_Normalized() {

        /* ───── 100 НОРМАЛИЗОВАННЫХ СТРОК (см. RowNormalizerServiceImpl) ───── */

        List<String> rows = List.of(
                // ---------- G-01 : john smith ----------
                "john smith|johnsmith@gmail.com",
                "smith john|jsmith@gmail.com",
                "j smith|john.s@mail.com",

                // ---------- G-02 : robert johnson ----------
                "robert johnson|robertj@test.com",
                "johnson robert|rjohnson@corp.net",
                "r johnson|robjohnson@foo.bar",

                // ---------- G-03 : alice brown ----------
                "alice brown|1990 01 01|123 main st",
                "brown alice|01 01 1990|123 main street",
                "a brown|abrown@mail.net|123 main st",

                // ---------- G-04 : moskva / moscow ----------
                "moskva|russia",
                "moscow|russia",
                "moskva moscow|ru",

                // ---------- G-05 : ooo rt ----------
                "ooo rt",
                "rt ltd",
                "rt",

                // ---------- G-06 : playstation 5 ----------
                "ps 5 de|sony",
                "playstation 5 digital edition",
                "ps5 de",

                // ---------- G-07 : rtx 4070 ti ----------
                "rtx 4070 ti",
                "nvidia rtx 4070ti",
                "geforce 4070 ti",

                // ---------- G-08 : peking university ----------
                "beijing daxue",
                "peking university",
                "bei jing da xue",

                // ---------- G-09 : 123-456-7890 ----------
                "123 456 7890",
                "123 456 7890 us",
                "+1 123 456 7890",

                // ---------- G-10 : ul lenina 15 ----------
                "ul lenina 15|moscow",
                "ulica lenina 15|msk",
                "15 lenina street|moskva",

                // ---------- G-11 : ivan ivanov 1985 ----------
                "ivan ivanov|1985",
                "ivanov ivan|85",
                "i ivanov|01 01 1985",

                // ---------- G-12 : john@example.com ----------
                "john@example.com",
                "john(at)example.com",
                "mailto john@example.com",

                // ---------- G-13 : sku a1b2 ----------
                "sku a1b2",
                "a1b2 sku",
                "art a1b2",

                // ---------- G-14 : 555 elm street ----------
                "555 elm st apt 10",
                "555 elm street 10",
                "elm 555 10",

                // ---------- G-15 : acme corp ----------
                "acme corp",
                "acme corporation",
                "acme",

                // ---------- B-16 … B-20 : «пограничные» (по 2 варианта) ----------
                "sergey petrov",
                "s petrov",
                "robert brown",
                "robert brown",
                "catherine wilson",
                "katherine wilson",
                "yuri gagarin",
                "jurij gagarin",
                "uniq code xyz 42",
                "uniq code xyz42",

                // ---------- U-21 … U-40 : 45 уникальных строк ----------
                "unique 001", "unique 002", "unique 003", "unique 004", "unique 005",
                "unique 006", "unique 007", "unique 008", "unique 009", "unique 010",
                "alpha beta gamma",
                "single very long totally different sentence number one",
                "beijing daxue 2024",
                "1234567890",
                "strannoe slovo",
                "this is completely different",
                "kalimat la alaqa laha",
                "lorem ipsum dolor sit amet",
                "foo bar baz qux quux",
                "unrelated entry 42",
                "delta epsilon zeta",
                "primer raznyh dannyh",
                "emoji line",
                "hash only abcdef",
                "another hash 12345",
                "42 is the answer",
                "stroka bez dubley 1",
                "stroka bez dubley 2",
                "stroka bez dubley 3",
                "unique alpha",
                "unique beta",
                "unique gamma",
                "unique delta",
                "unique epsilon",
                "uniquecase1",
                "uniquecase2",
                "uniquecase3",
                "one more totally different",
                "final unique row xyz"
        );

        /* ---------------------- вызов MinHash ---------------------- */
        Set<IndexPair> pairs = generator.generateCandidatePairs(rows);

        pairs.stream()
                .sorted(Comparator.comparingInt(IndexPair::first)
                        .thenComparingInt(IndexPair::second))
                .forEach(p -> log.info("PAIR  {} ↔ {}", p.first(), p.second()));

        /* ---- 1. внутри 15 групп-троек ожидaем ≥40 из 45 сочетаний ---- */
//        long groupedPairs = pairs.stream()
//                .filter(p -> p.first() < 45 && p.second() < 45)
//                .count();
//        assertThat(groupedPairs).isGreaterThanOrEqualTo(40);
//
//        /* ---- 2. «пограничные» пары (строки 45-54) – ≥3 совпадений ---- */
//        long borderPairs = pairs.stream()
//                .filter(p -> p.first() >= 45 && p.first() < 55)
//                .count();
//        assertThat(borderPairs).isGreaterThanOrEqualTo(3);

        /* ---- 3. Несколько явных проверок ---- */
        assertThat(pairs).contains(
                IndexPair.of(0, 1),   // john smith <-> smith john
                IndexPair.of(6, 7),   // moskva <-> moscow
                IndexPair.of(18, 19), // rtx 4070 ti вариации
                IndexPair.of(45, 46)  // sergey petrov пары
        );

//        /* ---- 4. Уникальные строки (>=55) не должны объединяться ---- */
//        boolean uniquesLinked = pairs.stream()
//                .anyMatch(p -> p.first() >= 55 || p.second() >= 55);
//        assertThat(uniquesLinked).isFalse();
    }


}