package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.impl.DuplicateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final double DEFAULT_SCORE_THRESHOLD = 0.9;
    private static final String CELL_SEPARATOR = "|";


    @Override
    public List<DuplicateMatchResponse> findDuplicates(DuplicateMatchRequest request) {
        List<String> normalizedRows = normalizeRows(request.rows());
        Map<Integer, List<Integer>> rowIndexToDuplicates = groupDuplicateRows(normalizedRows);

        return mapToResponses(normalizedRows, rowIndexToDuplicates);
    }

    private List<String> normalizeRows(List<List<String>> sheetRows) {
        List<String> normalizedRows = new ArrayList<>(sheetRows.size());

        for (List<String> row : sheetRows) {
            String mergedCells = String.join(CELL_SEPARATOR, row.stream().map(this::normalizeCell).toList());

            normalizedRows.add(mergedCells);
        }

        return normalizedRows;
    }

    private String normalizeCell(String cell) {
        return cell == null
                ? ""
                : cell.trim()
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    private Map<Integer, List<Integer>> groupDuplicateRows(List<String> normalizedRows) {
        int rowCount = normalizedRows.size();
        List<List<Integer>> similarityGraphs = computeSimilarityGraph(normalizedRows, rowCount);

        boolean[] rowVisited = new boolean[rowCount];
        Map<Integer, List<Integer>> originalRowIndexToGroupIndexes = new LinkedHashMap<>();

        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            if (rowVisited[rowIndex]) continue;

            List<Integer> duplicateRows = collectGroupDepthFirst(rowIndex, similarityGraphs, rowVisited);

            if (duplicateRows.size() > 1) {
                duplicateRows.sort(Integer::compare);
                originalRowIndexToGroupIndexes.put(duplicateRows.get(0), duplicateRows);
            }
        }

        return originalRowIndexToGroupIndexes;
    }

    private List<List<Integer>> computeSimilarityGraph(List<String> normalizedRows, int rowCount) {
        List<List<Integer>> similarityGraphs = new ArrayList<>(rowCount);

        for (int i = 0; i < rowCount; i++) {
            similarityGraphs.add(new ArrayList<>());
        }

        for (int left = 0; left < rowCount; left++) {

            for (int right = left + 1; right < rowCount; right++) {
                double similarityScore = exactMatchScore(normalizedRows.get(left), normalizedRows.get(right));

                if (similarityScore >= DEFAULT_SCORE_THRESHOLD) {
                    similarityGraphs.get(left).add(right);
                    similarityGraphs.get(right).add(left);
                }
            }
        }

        return similarityGraphs;
    }

    private List<Integer> collectGroupDepthFirst(int rowIndex, List<List<Integer>> similarityGraphs, boolean[] rowVisited) {
        List<Integer> groupIndexes = new ArrayList<>();
        Deque<Integer> nodesToVisitStack = new ArrayDeque<>();
        nodesToVisitStack.push(rowIndex);

        while (!nodesToVisitStack.isEmpty()) {
            int currentRow = nodesToVisitStack.pop();

            if (rowVisited[currentRow]) continue;

            rowVisited[currentRow] = true;
            groupIndexes.add(currentRow);

            for (int neighbourRow : similarityGraphs.get(currentRow)) {
                if (!rowVisited[neighbourRow]) nodesToVisitStack.push(neighbourRow);
            }
        }

        return groupIndexes;
    }

    private double exactMatchScore(String left, String right) {
        return left.equals(right) ? 1.0 : 0.0;
    }

    private List<DuplicateMatchResponse> mapToResponses(List<String> normalizedRows, Map<Integer, List<Integer>> rowIndexToDuplicates) {
        List<DuplicateMatchResponse> responses = new ArrayList<>(rowIndexToDuplicates.size());

        for (var entry : rowIndexToDuplicates.entrySet()) {
            int originalRowIndex = entry.getKey();
            List<Integer> duplicateRowIndexes = new ArrayList<>(entry.getValue());
            duplicateRowIndexes.remove(Integer.valueOf(originalRowIndex));

            List<String> originalCells = Arrays.stream(normalizedRows.get(originalRowIndex)
                            .split("\\|"))
                    .map(String::trim)
                    .toList();

            responses.add(new DuplicateMatchResponse(originalCells, originalRowIndex, duplicateRowIndexes));
        }

        return responses;
    }
}
