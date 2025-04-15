package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DuplicateDetectionService {

    private static final String COLUMN_DELIMITER = "␟";

    public List<DuplicateMatchResponse> findDuplicates(DuplicateMatchRequest request) {
        Map<String, Integer> rowSignatureToOriginalIndex = new HashMap<>();
        Map<String, List<Integer>> rowSignatureToDuplicateIndexes = new LinkedHashMap<>();

        List<List<String>> tableRows = request.rows();

        for (int rowIndex = 0; rowIndex < tableRows.size(); rowIndex++) {
            List<String> cleanedCells = cleanRow(tableRows.get(rowIndex));
            String rowSignature = concatenateCells(cleanedCells);

            if (rowSignatureToOriginalIndex.containsKey(rowSignature)) {
                rowSignatureToDuplicateIndexes
                        .computeIfAbsent(rowSignature, key -> new ArrayList<>(List.of(rowSignatureToOriginalIndex.get(key))))
                        .add(rowIndex);
            } else {
                rowSignatureToOriginalIndex.put(rowSignature, rowIndex);
            }
        }

        return buildDuplicateResponses(rowSignatureToDuplicateIndexes);
    }

    private List<String> cleanRow(List<String> row) {
        return row.stream()
                .map(this::normalizeCellContent)
                .toList();
    }

    private String normalizeCellContent(String cellContent) {
        if (cellContent == null) return "";

        String lowerCasedTrimmed = cellContent.trim().toLowerCase();
        StringBuilder normalizedBuilder = new StringBuilder(lowerCasedTrimmed.length());
        boolean previousWasSpace = false;

        for (char character : lowerCasedTrimmed.toCharArray()) {
            if (Character.isWhitespace(character)) {
                if (!previousWasSpace) {
                    normalizedBuilder.append(' ');
                    previousWasSpace = true;
                }
            } else {
                normalizedBuilder.append(character);
                previousWasSpace = false;
            }
        }

        return normalizedBuilder.toString();
    }

    private String concatenateCells(List<String> cells) {
        return String.join(COLUMN_DELIMITER, cells);
    }

    private List<DuplicateMatchResponse> buildDuplicateResponses(Map<String, List<Integer>> rowSignatureToDuplicateIndexes) {
        List<DuplicateMatchResponse> duplicateResponses = new ArrayList<>();

        for (Map.Entry<String, List<Integer>> entry : rowSignatureToDuplicateIndexes.entrySet()) {
            List<Integer> duplicateIndexes = entry.getValue();

            if (duplicateIndexes.size() > 1) {
                List<String> rowContents = Arrays.asList(entry.getKey().split(COLUMN_DELIMITER));
                int originalIndex = duplicateIndexes.get(0);
                List<Integer> duplicateOnlyIndexes = duplicateIndexes.subList(1, duplicateIndexes.size());

                duplicateResponses.add(new DuplicateMatchResponse(rowContents, originalIndex, duplicateOnlyIndexes));
            }
        }

        return duplicateResponses;
    }
}
