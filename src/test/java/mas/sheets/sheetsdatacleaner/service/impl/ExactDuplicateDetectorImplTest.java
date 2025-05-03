package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ExactDuplicateDetectorImplTest {

    @Autowired
    private ExactDuplicateDetectorImpl detector;

    private static long pairsInRange(Set<IndexPair<Integer, Integer>> pairs, int from, int to) {
        return pairs.stream()
                .filter(p -> p.first() >= from && p.first() < to
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
        List<List<Integer>> groups = result.duplicateGroups();

        // 1) ожидать 5, а не 3, групп
        assertEquals(5, groups.size(), "Должно быть обнаружено 5 групп дубликатов");

        // … проверки групп A, B, C остаются как есть …

        /* --- дополнительная проверка группы E′ (дата без цифр-only) --- */
        Optional<List<Integer>> groupE = findGroupContaining(groups, 13);
        assertTrue(groupE.isPresent(), "Группа E должна существовать");
        assertEquals(2, groupE.get().size());
        assertTrue(groupE.get().containsAll(List.of(13, 14)));

        /* --- дополнительная проверка группы F′ (паспорт без цифр-only) --- */
        Optional<List<Integer>> groupF = findGroupContaining(groups, 17);
        assertTrue(groupF.isPresent(), "Группа F должна существовать");
        assertEquals(2, groupF.get().size());
        assertTrue(groupF.get().containsAll(List.of(17, 18)));

        // 2) осталось 9 уникальных строк
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
     * Вспомогательный метод для поиска группы, содержащей указанный индекс
     */
    private Optional<List<Integer>> findGroupContaining(List<List<Integer>> groups, int index) {
        return groups.stream()
                .filter(group -> group.contains(index))
                .findFirst();
    }

    @Test
    void handlesEmptyInput() {
        // Проверка обработки пустого списка
        var result = detector.detect(Collections.emptyList());
        assertTrue(result.duplicateGroups().isEmpty(), "Для пустого ввода не должно быть групп дубликатов");
        assertTrue(result.remainingRows().isEmpty(), "Для пустого ввода не должно быть оставшихся строк");
        assertTrue(result.originalIndexes().isEmpty(), "Для пустого ввода не должно быть оригинальных индексов");
    }

    @Test
    void handlesNullInput() {
        // Проверка обработки null ввода
        var result = detector.detect(null);
        assertTrue(result.duplicateGroups().isEmpty(), "Для null ввода не должно быть групп дубликатов");
        assertTrue(result.remainingRows().isEmpty(), "Для null ввода не должно быть оставшихся строк");
        assertTrue(result.originalIndexes().isEmpty(), "Для null ввода не должно быть оригинальных индексов");
    }

    @Test
    void handlesMixedNullAndEmptyStrings() {
        // Проверка обработки смешанных null и пустых строк
        List<String> rows = Arrays.asList(null, "", null, "content", "");

        var result = detector.detect(rows);

        // null и пустые строки должны быть объединены в одну группу
        assertEquals(1, result.duplicateGroups().size(), "Должна быть одна группа дубликатов");
        assertEquals(4, result.duplicateGroups().getFirst().size(), "Группа должна содержать 4 элемента");

        // Только одна не-пустая строка должна остаться
        assertEquals(1, result.remainingRows().size(), "Должна остаться одна строка");
        assertEquals("content", result.remainingRows().getFirst(), "Оставшаяся строка должна быть 'content'");
    }

    @Test
    void performanceTest() {
        // Простой тест производительности для большого количества строк
        int size = 10_000;
        List<String> rows = new ArrayList<>(size);

        // Создаем много уникальных строк с несколькими дубликатами
        for (int i = 0; i < size; i++) {
            // 80% уникальных строк
            if (i % 5 != 0) {
                rows.add("unique_" + i);
            }
            // 20% дубликатов (каждый повторяется 4 раза)
            else {
                rows.add("duplicate_" + (i / 20));
            }
        }

        long startTime = System.currentTimeMillis();
        var result = detector.detect(rows);
        long endTime = System.currentTimeMillis();

        // Проверяем, что время выполнения в разумных пределах
        long executionTime = endTime - startTime;
        assertTrue(executionTime < 5000,
                "Время выполнения для 10K строк должно быть менее 5 секунд, но было " + executionTime + " мс");

        // Проверяем корректность результатов
        assertEquals(size / 20, result.duplicateGroups().size(),
                "Должно быть обнаружено правильное количество групп дубликатов");
    }
}
