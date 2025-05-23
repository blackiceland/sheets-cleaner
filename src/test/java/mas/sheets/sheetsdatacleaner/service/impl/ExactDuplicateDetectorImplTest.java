package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.model.RowNorm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ExactDuplicateDetectorImplTest {

    @Autowired
    private ExactDuplicateDetectorImpl detector;

    /* ===== helper: строки → RowNorm ===== */
    private static List<RowNorm> rn(List<String> rows) {
        List<RowNorm> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) out.add(new RowNorm(i, rows.get(i)));
        return out;
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

        var result = detector.detect(rn(rows));
        List<List<Integer>> groups = result.duplicateGroups();

        // 1) ожидать 5, а не 3, групп
        assertEquals(5, groups.size(), "Должно быть обнаружено 5 групп дубликатов");

        /* --- проверка группы E (даты) --- */
        Optional<List<Integer>> groupE = findGroupContaining(groups, 13);
        assertTrue(groupE.isPresent(), "Группа E должна существовать");
        assertEquals(2, groupE.get().size());
        assertTrue(groupE.get().containsAll(List.of(13, 14)));

        /* --- проверка группы F (паспорт) --- */
        Optional<List<Integer>> groupF = findGroupContaining(groups, 17);
        assertTrue(groupF.isPresent(), "Группа F должна существовать");
        assertEquals(2, groupF.get().size());
        assertTrue(groupF.get().containsAll(List.of(17, 18)));

        // 2) осталось 10 уникальных строк
        assertEquals(10, result.remainingRows().size(),
                "Должно остаться 10 уникальных строк");

        // 3) индексы, которые остались
        List<Integer> expectedRemaining = List.of(
                10, 11, 12,   // «123456» и вариации
                15,           // «20240101»
                16,           // «AB1234567»
                19, 20, 21, 22// шум
        );
        assertTrue(result.originalIndexes().containsAll(expectedRemaining),
                "Оставшиеся индексы должны соответствовать ожиданиям");
    }

    /**
     * helper: найти группу, содержащую индекс
     */
    private Optional<List<Integer>> findGroupContaining(List<List<Integer>> groups, int idx) {
        return groups.stream().filter(g -> g.contains(idx)).findFirst();
    }

    @Test
    void handlesEmptyInput() {
        var result = detector.detect(Collections.emptyList());
        assertTrue(result.duplicateGroups().isEmpty());
        assertTrue(result.remainingRows().isEmpty());
        assertTrue(result.originalIndexes().isEmpty());
    }

    @Test
    void handlesNullInput() {
        var result = detector.detect(null);
        assertTrue(result.duplicateGroups().isEmpty());
        assertTrue(result.remainingRows().isEmpty());
        assertTrue(result.originalIndexes().isEmpty());
    }

    @Test
    void handlesMixedNullAndEmptyStrings() {
        List<String> rows = Arrays.asList(null, "", null, "content", "");
        var result = detector.detect(rn(rows));

        // null и пустые строки объединяются в одну группу
        assertEquals(1, result.duplicateGroups().size());
        assertEquals(4, result.duplicateGroups().getFirst().size());

        // одна непустая строка остаётся
        assertEquals(1, result.remainingRows().size());
        assertEquals("content", result.remainingRows().getFirst().value());
    }

    @Test
    void performanceTest() {
        int size = 10_000;
        List<String> rows = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            if (i % 5 != 0) rows.add("unique_" + i);
            else rows.add("duplicate_" + (i / 20));
        }

        long start = System.currentTimeMillis();
        var result = detector.detect(rn(rows));
        long exec = System.currentTimeMillis() - start;

        assertTrue(exec < 5_000, "Время < 5 сек, было " + exec + " мс");
        assertEquals(size / 20, result.duplicateGroups().size());
    }
}
