package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DuplicateDetectionService {

    private static final String COLUMN_SEPARATOR = "␟";

    public List<DuplicateMatchResponse> findDuplicates(DuplicateMatchRequest request) {
        Map<String, Integer> uniqueRowIndexMap = new HashMap<>();
        Map<String, List<Integer>> duplicateRowIndexMap = new LinkedHashMap<>();

        List<List<String>> rows = request.rows();

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> normalizedRow = normalizeRow(rows.get(rowIndex));
            String serializedRow = serializeRow(normalizedRow);

            if (uniqueRowIndexMap.containsKey(serializedRow)) {
                duplicateRowIndexMap
                        .computeIfAbsent(serializedRow, key -> new ArrayList<>(List.of(uniqueRowIndexMap.get(key))))
                        .add(rowIndex);
            } else {
                uniqueRowIndexMap.put(serializedRow, rowIndex);
            }
        }

        return mapToResponse(duplicateRowIndexMap);
    }

    private List<String> normalizeRow(List<String> row) {
        return row.stream()
                .map(cell -> cell == null
                        ? ""
                        : cell.trim().replaceAll("\\s+", " ").toLowerCase()
                )
                .toList();
    }

    private String serializeRow(List<String> row) {
        return String.join(COLUMN_SEPARATOR, row);
    }

    private List<DuplicateMatchResponse> mapToResponse(Map<String, List<Integer>> duplicates) {
        List<DuplicateMatchResponse> result = new ArrayList<>();

        for (Map.Entry<String, List<Integer>> entry : duplicates.entrySet()) {
            List<Integer> rowIndexes = entry.getValue();

            if (rowIndexes.size() > 1) {
                List<String> normalizedRow = Arrays.asList(entry.getKey().split(COLUMN_SEPARATOR));
                int originalIndex = rowIndexes.get(0);
                List<Integer> duplicateIndexes = rowIndexes.subList(1, rowIndexes.size());

                result.add(new DuplicateMatchResponse(normalizedRow, originalIndex, duplicateIndexes));
            }
        }

        return result;
    }
}

