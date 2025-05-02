package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ExactDuplicateDetectorImplTest {

    @Autowired
    private ExactDuplicateDetectorImpl detector;
    
    private static long pairsInRange(Set<IndexPair<Integer,Integer>> pairs, int from, int to) {
        return pairs.stream()
                .filter(p -> p.first()  >= from && p.first()  < to
                        && p.second() >= from && p.second() < to)
                .count();
    }

    @Test
    void detectsExactDuplicatesAcrossManyGroups() {

        List<String> rows = List.of(
                /* ─── A: имя-фамилия ────────────────────────────────────── */
                "anton markov",              // 0
                "markov anton",              // 1
                "antonmarkov",               // 2
                "anton   markov",            // 3
                "markov.anton",              // 4

                /* ─── B: адрес «lenina street 15 moscow» ────────────────── */
                "lenina street 15 moscow",   // 5
                "moscow lenina street 15",   // 6
                "15 lenina street moscow",   // 7

                /* ─── C: точная одинаковая почта ────────────────────────── */
                "johnsmith@gmail.com",       // 8
                "johnsmith@gmail.com",       // 9

                /* ─── D: одинаковые цифры 123456 (K3) ───────────────────── */
                "123456",                    // 10
                "abc 123456",                // 11
                "id=123456",                 // 12

                /* ─── E: дата 2024-01-01, разные записи ─────────────────── */
                "2024-01-01",                // 13
                "2024 01 01",                // 14
                "20240101",                  // 15

                /* ─── F: паспорт-ID AB1234567 ───────────────────────────── */
                "AB1234567",                 // 16
                "ab-1234567",                // 17
                "ab 1234567",                // 18

                /* ─── шум, не дубли ─────────────────────────────────────── */
                "foo bar",                   // 19
                "completely different",      // 20
                "张伟",                       // 21
                "mhmd ibn ahmed"             // 22
        );

        var result = detector.detect(rows);
        Set<IndexPair<Integer,Integer>> pairs = result.exactPairs();

        assertThat(pairsInRange(pairs, 0, 5)).isGreaterThanOrEqualTo(1);

        assertThat(pairsInRange(pairs, 5, 8)).isGreaterThanOrEqualTo(1);

        assertThat(pairs).contains(IndexPair.ofNormalized(8, 9));

        assertThat(pairsInRange(pairs, 10, 13)).isGreaterThanOrEqualTo(3);

        assertThat(pairsInRange(pairs, 13, 16)).isGreaterThanOrEqualTo(1);

        assertThat(pairsInRange(pairs, 16, 19)).isGreaterThanOrEqualTo(1);

        assertThat(result.remainingRows())
                .contains("foo bar", "张伟", "mhmd ibn ahmed");

        assertThat(result.remainingRows().size()).isLessThan(rows.size());
    }
}
