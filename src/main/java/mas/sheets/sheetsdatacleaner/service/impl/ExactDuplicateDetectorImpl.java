package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");

    @Override
    public ExactDetectionResult detect(List<String> normalizedRows) {
        if (normalizedRows == null || normalizedRows.isEmpty()) {
            List<Integer> all = IntStream.range(0, 0)
                    .boxed()
                    .collect(Collectors.toList());
            return new ExactDetectionResult(
                    Collections.emptySet(),
                    normalizedRows == null ? Collections.emptyList() : normalizedRows,
                    all
            );
        }

        Map<String, List<Integer>> originalIndex = new HashMap<>();
        Map<String, List<Integer>> alnumOnlyIndex = new HashMap<>();

        for (int i = 0; i < normalizedRows.size(); i++) {
            String row = normalizedRows.get(i);
            if (row == null || row.isEmpty()) continue;

            originalIndex
                    .computeIfAbsent(row, k -> new ArrayList<>())
                    .add(i);

            String alnum = NON_ALNUM.matcher(row).replaceAll("");
            if (!alnum.isEmpty()) {
                alnumOnlyIndex
                        .computeIfAbsent(alnum, k -> new ArrayList<>())
                        .add(i);
            }
        }

        Set<IndexPair<Integer, Integer>> exactPairs = new HashSet<>();
        collectPairs(originalIndex, exactPairs);
        collectPairs(alnumOnlyIndex, exactPairs);

        Set<Integer> toSkip = exactPairs.stream()
                .flatMap(p -> Arrays.stream(new Integer[]{p.first(), p.second()}))
                .collect(Collectors.toSet());

        List<String> remainingRows = new ArrayList<>();
        List<Integer> originalIndexes = new ArrayList<>();
        for (int i = 0; i < normalizedRows.size(); i++) {
            if (!toSkip.contains(i)) {
                remainingRows.add(normalizedRows.get(i));
                originalIndexes.add(i);
            }
        }

        return new ExactDetectionResult(exactPairs, remainingRows, originalIndexes);
    }

    private void collectPairs(Map<String, List<Integer>> index,
                              Set<IndexPair<Integer, Integer>> out) {
        for (List<Integer> bucket : index.values()) {
            if (bucket.size() < 2) continue;
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    out.add(IndexPair.ofNormalized(bucket.get(i), bucket.get(j)));
                }
            }
        }
    }

    public record ExactDetectionResult(
            Set<IndexPair<Integer, Integer>> exactPairs,
            List<String> remainingRows,
            List<Integer> originalIndexes
    ) {
    }
}
