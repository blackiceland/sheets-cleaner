package mas.sheets.sheetsdatacleaner.generator;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class TestDatasetBuilder {

    private TestDatasetBuilder() {}

    public static List<List<String>> build(int rows, int columns, int groups) {
        List<List<String>> result = new ArrayList<>(rows);
        for (int i = 0; i < groups; i++) {
            result.addAll(duplicateTriple(columns));
        }
        int uniques = rows - groups * 3;
        for (int i = 0; i < uniques; i++) {
            result.add(uniqueRow(columns));
        }
        Collections.shuffle(result, new Random(42));
        return result;
    }

    private static List<List<String>> duplicateTriple(int columns) {
        String name = randomName();
        String email = name.toLowerCase().replace(' ', '.') + "@test.com";
        List<List<String>> triple = new ArrayList<>(3);
        triple.add(buildRow(columns, name, email, "v1"));
        triple.add(buildRow(columns, name, email.replace("@", "1@"), "v2"));
        triple.add(buildRow(columns, name.substring(0, 1) + ". " + name.split(" ")[1], email, "v3"));
        return triple;
    }

    private static List<String> buildRow(int columns, String partA, String partB, String partC) {
        return switch (columns) {
            case 1 -> List.of(partA + " " + partB + " " + partC);
            case 2 -> List.of(partA, partB);
            default -> List.of(partA, partB, LocalDate.now().format(DateTimeFormatter.ISO_DATE));
        };
    }

    private static List<String> uniqueRow(int columns) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        return switch (columns) {
            case 1 -> List.of("unique " + token);
            case 2 -> List.of("unique", token);
            default -> List.of("unique", token, String.valueOf(ThreadLocalRandom.current().nextInt()));
        };
    }

    private static String randomName() {
        String[] fn = {"John", "Maria", "Robert", "Sarah"};
        String[] ln = {"Smith", "Brown", "Taylor", "Davis"};
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return fn[r.nextInt(fn.length)] + " " + ln[r.nextInt(ln.length)];
    }
}