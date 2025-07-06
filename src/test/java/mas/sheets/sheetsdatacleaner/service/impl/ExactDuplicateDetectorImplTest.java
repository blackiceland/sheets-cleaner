package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ExactDuplicateDetectorImplTest {

    @Autowired
    private ExactDuplicateDetectorImpl detector;

    /* helper: строки → RowNorm с индексом */
    private static List<RowNorm> toRowNorm(List<String> rows) {
        List<RowNorm> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) out.add(RowNorm.of(i, rows.get(i)));
        return out;
    }

    /* ─────────────────────────── CORE CASE ───────────────────────── */

    @Test
    void detectsExactDuplicatesAcrossManyGroups() {

        List<String> rows = List.of(
                /* ─── A: имя-фамилия ─────────────────────────────── */
                "anton markov",           // 0
                "markov anton",           // 1
                "antonmarkov",            // 2
                "anton   markov",         // 3
                "markov.anton",           // 4

                /* ─── B: адрес ───────────────────────────────────── */
                "lenina street 15 moscow", // 5
                "moscow lenina street 15", // 6
                "15 lenina street moscow", // 7

                /* ─── C: почта ───────────────────────────────────── */
                "johnsmith@gmail.com",     // 8
                "johnsmith@gmail.com",     // 9

                /* ─── D: 123456 ─────────────────────────────────── */
                "123456",                 // 10
                "abc 123456",             // 11
                "id=123456",              // 12

                /* ─── E: 2024-01-01 ─────────────────────────────── */
                "2024-01-01",             // 13
                "2024 01 01",             // 14
                "20240101",               // 15

                /* ─── F: паспорт ────────────────────────────────── */
                "AB1234567",              // 16
                "ab-1234567",             // 17
                "ab 1234567",             // 18

                /* ─── шум ───────────────────────────────────────── */
                "foo bar",                // 19
                "completely different",   // 20
                "张伟",                    // 21
                "mhmd ibn ahmed"          // 22
        );

        var result = detector.detect(toRowNorm(rows));

        List<List<Integer>> groups = result.duplicateGroups();

        /* 1. Ожидаем 4 кластеров (A-E) */
        assertEquals(4, groups.size(), "Должно быть обнаружено 4 групп дубликатов");

        /* 2. Группа Е (даты) содержит 13 и 14 */
        var groupE = groupContaining(groups, 13);
        assertTrue(groupE.isPresent(), "Группа E должна существовать");
        assertEquals(Set.of(13, 14), new HashSet<>(groupE.get()));

        /* 3. Группа F (паспорт) содержит 17 и 18 */
        var groupF = groupContaining(groups, 17);
        assertTrue(groupF.isPresent(), "Группа F должна существовать");
        assertEquals(Set.of(17, 18), new HashSet<>(groupF.get()));

        /* 4. Осталось 15 уникальных строк */
        assertEquals(15, result.remainRows().size(), "Должно остаться 15 уникальных строк");

        /* 5. Проверяем, что именно эти индексы остались */
        List<Integer> expectedRemaining = List.of(
                10, 11, 12,   // «123456» и вариации
                15,           // «20240101»
                16,           // «AB1234567»
                19, 20, 21, 22
        );
        assertTrue(result.remainIdxSrc().containsAll(expectedRemaining),
                "Оставшиеся индексы должны соответствовать ожиданиям");

        /* 6. Метаданные есть для всех строк результата */
        int metaCount = result.metaByIdx().size();
        assertEquals(8, metaCount, "metaByIdx должен содержать запись на каждый исходный индекс");

        /* 7. Проверяем, что у 13-й строки в meta стоит тот же clusterId,
              что и у 14-й (доказываем объединение) */
        RowMeta m13 = result.metaByIdx().get(13);
        RowMeta m14 = result.metaByIdx().get(14);
        assertNotNull(m13);
        assertNotNull(m14);
        assertEquals(m13.clusterId(), m14.clusterId(),
                "Элементы кластера E должны иметь единый clusterId");
    }

    /* ───────────────────────── edge-cases ────────────────────────── */

    @Test
    void handlesEmptyInput() {
        var r = detector.detect(Collections.emptyList());
        assertAll(
                () -> assertTrue(r.duplicateGroups().isEmpty()),
                () -> assertTrue(r.remainRows().isEmpty()),
                () -> assertTrue(r.remainIdxSrc().isEmpty()),
                () -> assertTrue(r.metaByIdx().isEmpty())
        );
    }

    @Test
    void handlesNullInput() {
        var r = detector.detect(null);
        assertAll(
                () -> assertTrue(r.duplicateGroups().isEmpty()),
                () -> assertTrue(r.remainRows().isEmpty()),
                () -> assertTrue(r.remainIdxSrc().isEmpty()),
                () -> assertTrue(r.metaByIdx().isEmpty())
        );
    }

    @Test
    void handlesMixedNullAndEmptyStrings() {
        List<String> rows = Arrays.asList(null, "", null, "content", "");
        var r = detector.detect(toRowNorm(rows));

        /* null и "" склеились в одну группу */
        assertEquals(1, r.duplicateGroups().size());
        assertEquals(4, r.duplicateGroups().getFirst().size());

        /* одна непустая строка осталась */
        assertEquals(1, r.remainRows().size());
        assertEquals("content", r.remainRows().getFirst().value());
    }

    /* ───────────── simple perf sanity (10k строк < 5 s) ──────────── */

    @Test
    void performanceTest() {
        int size = 10_000;
        List<String> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(i % 5 == 0 ? "duplicate_" + (i / 20)
                    : "unique_"    + i);
        }

        long t0 = System.currentTimeMillis();
        var r = detector.detect(toRowNorm(rows));
        long ms = System.currentTimeMillis() - t0;

        assertAll(
                () -> assertTrue(ms < 5_000, "Время < 5 сек, было " + ms + " мс"),
                () -> assertEquals(size / 20, r.duplicateGroups().size())
        );
    }

    /* ────────────────────────── helpers ──────────────────────────── */

    private static Optional<List<Integer>> groupContaining(List<List<Integer>> groups, int idx) {
        return groups.stream().filter(g -> g.contains(idx)).findFirst();
    }
}
