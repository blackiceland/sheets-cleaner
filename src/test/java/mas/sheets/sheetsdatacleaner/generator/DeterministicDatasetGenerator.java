package mas.sheets.sheetsdatacleaner.generator;

import java.util.*;

public final class DeterministicDatasetGenerator {

    private static final int TOTAL_ROWS           = 1000;
    private static final int EXACT_GROUPS         = 150;  // 3 идентичные строки
    private static final int BORDERLINE_GROUPS    = 100;  // 3 варианта
    private static final int UNIQUE_ROWS          = 250;  // 1-к-1

    private DeterministicDatasetGenerator() {}

    public static List<List<String>> build() {
        List<List<String>> rows = new ArrayList<>(TOTAL_ROWS);
        Random rnd = new Random(42);

        /* 1. Полные дубликаты --------------------------------------- */
        for (int g = 0; g < EXACT_GROUPS; g++) {
            String v = "EXACT_DUP_" + String.format("%03d", g);
            for (int i = 0; i < 3; i++) {
                rows.add(List.of(v));
            }
        }

        /* 2. Пограничные дубликаты ---------------------------------- */
        for (int g = 0; g < BORDERLINE_GROUPS; g++) {
            String base = "Item " + (1000 + g) + " – premium quality";
            rows.add(List.of(base));
            rows.add(List.of(base.toUpperCase()));
            rows.add(List.of("premium quality " + (1000 + g) + " item"));
        }

        /* 3. Уникальные строки -------------------------------------- */
        for (int i = 0; i < UNIQUE_ROWS; i++) {
            rows.add(List.of("UNIQUE_" + i));
        }

        Collections.shuffle(rows, rnd);

        return rows;
    }
}