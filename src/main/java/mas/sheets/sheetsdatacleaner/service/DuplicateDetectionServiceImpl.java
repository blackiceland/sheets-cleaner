package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.impl.DuplicateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private static final double DEFAULT_SCORE_THRESHOLD = 0.9;
    private static final String CELL_SEPARATOR = "|";

    @Override
    public List<DuplicateMatchResponse> findDuplicates(DuplicateMatchRequest request) {
        if (request.rows() == null || request.rows().isEmpty()) {
            return List.of();
        }

        List<String> normalizedRows = normalizeRows(request.rows());
        Map<Integer, List<Integer>> rowIndexToDuplicates = groupDuplicateRows(normalizedRows);

        return mapToResponses(normalizedRows, rowIndexToDuplicates);
    }

    private List<String> normalizeRows(List<List<String>> sheetRows) {
        List<String> normalizedRows = new ArrayList<>(sheetRows.size());

        for (List<String> row : sheetRows) {
            List<String> normalizedRow = row.stream()
                    .map(this::normalizeCell)
                    .filter(cell -> !cell.isEmpty())
                    .toList();

            String mergedCells = String.join(CELL_SEPARATOR, normalizedRow);

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
        Map<String, List<Integer>> firstCellValueToRowIndexes = buildFirstCellValueToRowIndexesMap(normalizedRows);

        boolean[] rowVisited = new boolean[rowCount];
        Map<Integer, List<Integer>> originalRowIndexToGroupIndexes = new LinkedHashMap<>();

        for (List<Integer> groupRowIndexes : firstCellValueToRowIndexes.values()) {
            if (groupRowIndexes.size() < 2) {
                continue;
            }

            List<List<Integer>> similarityGraphs = computeSimilarityGraph(normalizedRows, groupRowIndexes);

            for (int i = 0; i < groupRowIndexes.size(); i++) {
                int rowIndex = groupRowIndexes.get(i);

                if (rowVisited[rowIndex]) {
                    continue;
                }

                List<Integer> duplicateRows = collectGroupDepthFirst(i, similarityGraphs, groupRowIndexes, rowVisited);

                if (duplicateRows.size() > 1) {
                    duplicateRows.sort(Integer::compare);
                    originalRowIndexToGroupIndexes.put(duplicateRows.get(0), duplicateRows);
                }
            }
        }

        return originalRowIndexToGroupIndexes;
    }

    private Map<String, List<Integer>> buildFirstCellValueToRowIndexesMap(List<String> rows) {
        Map<String, List<Integer>> firstCellToRowIndexesMap = new HashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            String[] parts = rows.get(i).split(Pattern.quote(CELL_SEPARATOR));
            String key = parts.length > 0 ? parts[0].trim().toLowerCase() : "";

            firstCellToRowIndexesMap.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        return firstCellToRowIndexesMap;
    }

    private List<List<Integer>> computeSimilarityGraph(List<String> normalizedRows, List<Integer> groupRowIndexes) {
        int size = groupRowIndexes.size();
        List<Queue<Integer>> concurrentGraphs = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            concurrentGraphs.add(new ConcurrentLinkedQueue<>());
        }

        IntStream.range(0, size).parallel().forEach(i -> {
            for (int j = i + 1; j < size; j++) {
                int left = groupRowIndexes.get(i);
                int right = groupRowIndexes.get(j);
                double similarityScore = exactMatchScore(normalizedRows.get(left), normalizedRows.get(right));

                if (similarityScore >= DEFAULT_SCORE_THRESHOLD) {
                    concurrentGraphs.get(i).add(j);
                    concurrentGraphs.get(j).add(i);
                }
            }
        });

        return concurrentGraphs.stream()
                .map(ArrayList::new)
                .collect(Collectors.toList());
    }

    private List<Integer> collectGroupDepthFirst(int localIndex, List<List<Integer>> similarityGraphs, List<Integer> bucket, boolean[] rowVisited) {
        List<Integer> groupIndexes = new ArrayList<>();
        Deque<Integer> nodesToVisitStack = new ArrayDeque<>();
        nodesToVisitStack.push(localIndex);

        while (!nodesToVisitStack.isEmpty()) {
            int currentLocal = nodesToVisitStack.pop();
            int currentGlobal = bucket.get(currentLocal);

            if (rowVisited[currentGlobal]) {
                continue;
            }

            rowVisited[currentGlobal] = true;
            groupIndexes.add(currentGlobal);

            for (int neighbourLocal : similarityGraphs.get(currentLocal)) {
                int neighbourGlobal = bucket.get(neighbourLocal);

                if (!rowVisited[neighbourGlobal]) {
                    nodesToVisitStack.push(neighbourLocal);
                }
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
                            .split(Pattern.quote(CELL_SEPARATOR)))
                    .map(String::trim)
                    .toList();

            responses.add(new DuplicateMatchResponse(originalCells, originalRowIndex, duplicateRowIndexes));
        }

        return responses;
    }
}
