package mas.sheets.sheetsdatacleaner.service;

import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Slf4j
@ActiveProfiles("test")
class MinHashCandidateDetectionServiceTest {

    @Autowired
    private MinHashCandidateDetectionService generator;

    private static List<RowNorm> rn(List<String> rows) {
        List<RowNorm> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) out.add(RowNorm.of(i, rows.get(i)));
        return out;
    }

    @Test
    void testDuplicateDetectionWithRealExamples() {

        List<String> testRows = Arrays.asList(
                // … содержимое осталось прежним …
                "anton | markov",
                "markov | anton",
                "anton markov | antonmarkov@gmail.com",
                "a. markov | anton@gmail.com",
                "resume | facade | creme brulee",
                "zhang wei | mhmd | ivan ivanov",
                "",
                "a b | a b | c d",
                "aleksei petrov | aleksei.petrov@mail.ru",
                "a petrov | aleksei.petrov+test@mail.ru",
                "ul. lenina 15 | lenina street 15 | moscow",
                "ulica lenina d. 15 | 15 lenina | msk",
                "moskva | moscow",
                "moskva | moscow | russia",
                "john | smith",
                "j. smith",
                "ivan ivanov | 1985",
                "ivanov ivan | 85",
                "no duplicates here",
                "completely | different | row",
                "anton markov",
                "markov anton",
                "alex petrov | moskovskaya 12 | 01.01.1990",
                "sergey petrov | tverskaya 8 | 02.02.1992",
                "ivan petrov | ivanov petr"
        );

        Set<IndexPair> candidateIndexPairs =
                generator.generateCandidatePairs(rn(testRows));

        assertThat(candidateIndexPairs).contains(
                IndexPair.of(0, 1),
                IndexPair.of(0, 2),
                IndexPair.of(0, 20),
                IndexPair.of(1, 21)
        );

        List<IndexPair> expectedIndexPairs = Arrays.asList(
                IndexPair.of(0, 3),
                IndexPair.of(2, 3),
                IndexPair.of(2, 20),
                IndexPair.of(8, 9),
                IndexPair.of(10, 11),
                IndexPair.of(12, 13),
                IndexPair.of(14, 15),
                IndexPair.of(16, 17),
                IndexPair.of(20, 21)
        );

        long matchCount = expectedIndexPairs.stream()
                .filter(candidateIndexPairs::contains)
                .count();
        assertThat(matchCount).isGreaterThanOrEqualTo(7);

        assertThat(candidateIndexPairs).doesNotContain(
                IndexPair.of(18, 19),
                IndexPair.of(23, 24)
        );

        boolean emptyMatched = candidateIndexPairs.stream()
                .anyMatch(p -> p.first() == 6 || p.second() == 6);
        assertThat(emptyMatched).isFalse();
    }

    @Test
    void shouldReturnNoCandidatesForCompletelyDifferentStrings() {
        List<String> rows = List.of(
                "elephant in the room",
                "北京大学招生简章",
                "1234567890!@#$%^",
                "очень странное слово",
                "this.is:completely;different",
                "كلمات لا علاقة لها ببعضها"
        );

        Set<IndexPair> pairs = generator.generateCandidatePairs(rn(rows));

        assertThat(pairs).isEmpty();
    }

    @Test
    void shouldDetectNameVariations() {
        List<String> rows = List.of(
                "john smith",
                "smith john",
                "j smith",
                "johnny smith",
                "john smyth",
                "completely different"
        );

        Set<IndexPair> pairs = generator.generateCandidatePairs(rn(rows));

        assertThat(pairs).contains(IndexPair.of(0, 1));

        long namePairs = pairs.stream()
                .filter(p -> p.first() < 5 && p.second() < 5)
                .count();
        assertThat(namePairs).isGreaterThanOrEqualTo(4);

        assertThat(pairs).noneMatch(p -> p.first() == 5 || p.second() == 5);
    }

    @Test
    void shouldDetectPhoneNumberVariations() {
        List<String> rows = List.of(
                "123 456 7890",
                "123-456-7890",
                "(123) 456-7890",
                "+1 123 456 7890",
                "123.456.7890",
                "987 654 3210"
        );

        Set<IndexPair> pairs = generator.generateCandidatePairs(rn(rows));

        assertThat(pairs).contains(IndexPair.of(0, 1));
        assertThat(pairs).noneMatch(p -> p.first() == 5 || p.second() == 5);
    }

    @Test
    void shouldHandleMultipartRows() {

        List<String> rows = List.of(
                // … данные из исходного теста …
                "john smith | john.smith@example.com | 123 main st",
                "john smith | jsmith@example.com | 123 main street",
                "smith john | john.s@example.com | 123 main st apt 4b",
                "j smith | johnsmith@gmail.com | 123 main st apartment 4",
                "alice jones | alice@example.com | 456 oak ave",
                "alice j | a.jones@example.com | 456 oak avenue",
                "a jones | alice.j@company.com | 456 oak",
                "michael johnson | mike@test.com | 789 pine rd",
                "mike johnson | mjohnson@mail.com | 789 pine road",
                "johnson michael | m.j@test.org | 789 pine",
                "david wilson | d.wilson@example.net | 101 maple street",
                "sarah wilson | swilson@example.net | 101 maple st",
                "robert brown | rbrown@test.com | 555 elm street apt 10",
                "emily white | ewhite@mail.org | 555 elm street #10",
                "jennifer adams | jadams@mail.net | 999 birch road",
                "christopher martin | cmartin@example.com | 333 spruce ave"
        );

        Set<IndexPair> pairs = generator.generateCandidatePairs(rn(rows));

        pairs.stream()
                .sorted(Comparator.comparingInt(IndexPair::first)
                        .thenComparingInt(IndexPair::second))
                .forEach(p -> log.info("PAIR  {} ↔ {}", p.first(), p.second()));

        assertThat(pairs).contains(
                IndexPair.of(0, 1), IndexPair.of(0, 2), IndexPair.of(0, 3),
                IndexPair.of(1, 2), IndexPair.of(1, 3), IndexPair.of(2, 3),
                IndexPair.of(4, 5), IndexPair.of(4, 6), IndexPair.of(5, 6),
                IndexPair.of(7, 8), IndexPair.of(7, 9), IndexPair.of(8, 9),
                IndexPair.of(10, 11)
        );

        assertThat(pairs).noneMatch(p -> p.first() >= 14 || p.second() >= 14);
        assertThat(pairs.size()).isGreaterThanOrEqualTo(13);
    }

    @Test
    void shouldDetectDuplicatesInDeterministicHundredRows_Normalized() {

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

        Set<IndexPair> pairs = generator.generateCandidatePairs(rn(rows));

        pairs.stream()
                .sorted(Comparator.comparingInt(IndexPair::first)
                        .thenComparingInt(IndexPair::second))
                .forEach(p -> log.info("PAIR  {} ↔ {}", p.first(), p.second()));

        assertThat(pairs).contains(
                IndexPair.of(0, 1),
                IndexPair.of(6, 7),
                IndexPair.of(18, 19),
                IndexPair.of(45, 46)
        );
    }
}
