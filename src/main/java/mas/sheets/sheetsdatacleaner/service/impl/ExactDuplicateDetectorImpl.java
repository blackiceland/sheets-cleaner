package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public ExactDetectionResult detect(List<String> normalizedRows) {
        Map<String, List<Integer>> k0 = new HashMap<>();
        Map<String, List<Integer>> k1 = new HashMap<>();
        Map<String, List<Integer>> k2 = new HashMap<>();
        Map<String, List<Integer>> k3 = new HashMap<>();

        for (int i = 0; i < normalizedRows.size(); i++) {
            String row = normalizedRows.get(i);

            put(k0, row, i);
            put(k1, sortTokens(row), i);
            put(k2, squeezeAlnum(row), i);

            String digitsKey = digitsOnly(row);

            if (digitsKey.length() >= 4) {
                put(k3, digitsKey, i);
            }
        }

        Set<IndexPair<Integer, Integer>> exactPairs = new HashSet<>();
        collectPairs(k0, exactPairs);
        collectPairs(k1, exactPairs);
        collectPairs(k2, exactPairs);
        collectPairs(k3, exactPairs);

        Set<Integer> rowsToSkip = exactPairs.stream()
                .flatMap(pair -> Arrays.stream(new Integer[]{pair.first(), pair.second()}))
                .collect(Collectors.toSet());

        List<String> remainingRows = new ArrayList<>(normalizedRows.size() - rowsToSkip.size());
        List<Integer> indexMap = new ArrayList<>(normalizedRows.size() - rowsToSkip.size());

        for (int i = 0; i < normalizedRows.size(); i++) {
            if (!rowsToSkip.contains(i)) {
                indexMap.add(i);
                remainingRows.add(normalizedRows.get(i));
            }
        }

        return new ExactDetectionResult(exactPairs, remainingRows, indexMap);
    }

    private static void put(Map<String, List<Integer>> map, String key, int idx) {
        map.computeIfAbsent(key, __ -> new ArrayList<>(2)).add(idx);
    }

    private static void collectPairs(Map<String, List<Integer>> map, Set<IndexPair<Integer, Integer>> out) {
        for (List<Integer> bucket : map.values()) {

            if (bucket.size() < 2) continue;

            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    out.add(IndexPair.ofNormalized(bucket.get(i), bucket.get(j)));
                }
            }
        }
    }

    private static String sortTokens(String row) {
        String[] tokens = WHITESPACE.split(row.trim());

        return Arrays.stream(tokens)
                .filter(t -> !t.equals("|"))
                .sorted()
                .collect(Collectors.joining(" "));
    }

    private static String squeezeAlnum(String row) {
        String cleaned = NON_ALNUM.matcher(row).replaceAll("").toLowerCase(Locale.ROOT);

        return Normalizer.normalize(cleaned, Normalizer.Form.NFKC);
    }

    private static String digitsOnly(String row) {
        return row.replaceAll("\\D", "");
    }

    public record ExactDetectionResult(
            Set<IndexPair<Integer, Integer>> exactPairs,
            List<String> remainingRows,
            List<Integer> originalIndexes
    ) {}

}
