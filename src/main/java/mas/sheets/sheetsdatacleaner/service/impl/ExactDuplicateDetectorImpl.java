package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.enums.ClusterKind;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Component
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern SEP = Pattern.compile("[-_/+]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    private static String canonical(String s) {
        if (s == null) return "";
        String t = ZERO_WIDTH.matcher(s).replaceAll("");
        t = SEP.matcher(t).replaceAll(" ");
        t = SPACE.matcher(t.trim()).replaceAll(" ");
        return t.toLowerCase(Locale.ROOT);
    }

    @Override
    public ExactDetectionResult detect(List<RowNorm> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ExactDetectionResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap()
            );
        }

        int n = rows.size();
        String[] keys = new String[n];
        IntStream.range(0, n).parallel().forEach(i -> keys[i] = canonical(rows.get(i).value()));

        Map<String, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            buckets.computeIfAbsent(keys[i], k -> new ArrayList<>()).add(i);
        }

        List<List<Integer>> duplicateGroups = new ArrayList<>();
        List<RowNorm> remainRows = new ArrayList<>();
        List<Integer> remainIdxSrc = new ArrayList<>();
        Map<Integer, RowMeta> metaByIdx = new HashMap<>();

        for (List<Integer> positions : buckets.values()) {
            if (positions.size() > 1) {
                UUID clusterId = UUID.randomUUID();
                int firstPos = positions.getFirst();
                RowNorm firstRow = rows.get(firstPos);
                metaByIdx.put(firstRow.idx(), new RowMeta(firstRow.idx(), clusterId, ClusterKind.CANON));
                for (int j = 1; j < positions.size(); j++) {
                    RowNorm r = rows.get(positions.get(j));
                    metaByIdx.put(r.idx(), new RowMeta(r.idx(), clusterId, ClusterKind.EXACT));
                }
                duplicateGroups.add(positions.stream().map(p -> rows.get(p).idx()).toList());
            } else {
                int pos = positions.getFirst();
                remainRows.add(rows.get(pos));
                remainIdxSrc.add(rows.get(pos).idx());
            }
        }

        return new ExactDetectionResult(duplicateGroups, remainRows, remainIdxSrc, metaByIdx);
    }
}
