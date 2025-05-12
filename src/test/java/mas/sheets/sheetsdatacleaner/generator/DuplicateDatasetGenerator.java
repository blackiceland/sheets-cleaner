package mas.sheets.sheetsdatacleaner.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генератор контролируемого датасета — ровно 1000 строк,
 * • 250 групп × 3 вариации = 750 дубликатов;<br>
 * • 250 уникальных строк;<br>
 * каждый элемент — {@code List<String>} (готово для DuplicateMatchRequest).
 */
public final class DuplicateDatasetGenerator {

    /* ---------- параметры ---------- */
    private static final int TOTAL = 1_000;
    private static final int GROUPS = 250;
    private static final int VARIATIONS = 3;
    private static final int UNIQUES = TOTAL - GROUPS * VARIATIONS;

    private static final String[] FN = {"John", "Maria", "Robert", "Sarah", "Michael",
            "Jennifer", "David", "Elizabeth", "James", "Patricia", "William", "Linda"};
    private static final String[] LN = {"Smith", "Garcia", "Johnson", "Williams", "Brown",
            "Jones", "Miller", "Davis", "Wilson", "Taylor", "Anderson", "Thomas"};
    private static final String[] DM = {"example.com", "gmail.com", "company.com", "mail.com"};
    /* -------------------------------- */

    private DuplicateDatasetGenerator() {
    }

    /* -------- публичный API -------- */

    /**
     * Возвращает список из 1000 строк.
     */
    public static List<List<String>> build() {
        List<List<String>> rows = new ArrayList<>(TOTAL);

        /* 1) 250 групп дубликатов (по 3 строки) */
        for (int i = 0; i < GROUPS; i++) rows.addAll(personVariations());

        /* 2) 250 уникальных строк */
        for (int i = 0; i < UNIQUES; i++) rows.add(uniqueRow());

        Collections.shuffle(rows, new Random(42));      // стабильная перестановка

        return rows;
    }

    /**
     * Сохраняет датасет в файл (UTF-8, по строке на запись).
     */
    public static void toFile(Path file) throws IOException {
        try (var out = Files.newBufferedWriter(file)) {
            for (List<String> row : build()) {
                out.write(String.join(" | ", row));
                out.newLine();
            }
        }
    }

    /* ----------- helpers ----------- */

    /**
     * 3 вариации записи «контакт» для проверки MinHash.
     */
    private static List<List<String>> personVariations() {
        var rnd = ThreadLocalRandom.current();
        String fn = FN[rnd.nextInt(FN.length)];
        String ln = LN[rnd.nextInt(LN.length)];
        String dom = DM[rnd.nextInt(DM.length)];
        String email = fn.toLowerCase() + '.' + ln.toLowerCase() + '@' + dom;

        List<List<String>> v = new ArrayList<>(VARIATIONS);

        /* 1 — имя фамилия + email */
        v.add(List.of(fn + ' ' + ln, email));

        /* 2 — фамилия, имя + alias email */
        v.add(List.of(ln + ", " + fn,
                fn.charAt(0) + ln.toLowerCase() + '@' + dom));

        /* 3 — инициалы + синоним домена */
        String altDom = DM[(rnd.nextInt(DM.length - 1) + 1) % DM.length];
        v.add(List.of(fn.charAt(0) + ". " + ln,
                fn.toLowerCase().charAt(0) + ln.toLowerCase() + '@' + altDom));

        return v;
    }

    /**
     * Строка, гарантированно не имеющая дубликатов.
     */
    private static List<String> uniqueRow() {
        var rnd = ThreadLocalRandom.current();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        LocalDate d = LocalDate.now().minusDays(rnd.nextInt(365));
        String txt = switch (rnd.nextInt(3)) {
            case 0 -> "unique record " + uid;
            case 1 -> d.format(DateTimeFormatter.ISO_DATE);
            default -> "no-dup " + rnd.nextInt(1_000_000);
        };
        return List.of(txt);
    }

    /* -------- Точка входа для ручного запуска -------- */
    public static void main(String[] args) throws IOException {
        toFile(Path.of("dataset_1000.txt"));
        System.out.println("Dataset written → dataset_1000.txt   (1000 rows)");
    }
}
