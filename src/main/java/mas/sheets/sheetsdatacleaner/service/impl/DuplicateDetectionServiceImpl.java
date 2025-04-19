package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.SimilarityGraphBuilder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static mas.sheets.sheetsdatacleaner.util.RowNormalizer.normalizeRows;


@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private final SimilarityGraphBuilder similarityGraphBuilder;


    @Override
    public List<DuplicateMatchResponse> findDuplicates(DuplicateMatchRequest request) {
        if (request.rows() == null || request.rows().isEmpty()) {
            return Collections.emptyList();
        }

        List<List<String>> normalizedRows = normalizeRows(request.rows());
        Map<String, List<Integer>> firstCellValueToRowIndexes = groupRowsByFirstCell(normalizedRows);
        Map<Integer, List<Integer>> mainRowIndexToDuplicateIndexes = collectDuplicateRowGroups(normalizedRows, firstCellValueToRowIndexes);

        return buildResponses(normalizedRows, mainRowIndexToDuplicateIndexes);
    }

    private Map<String, List<Integer>> groupRowsByFirstCell(List<List<String>> normalizedRows) {
        Map<String, List<Integer>> firstCellValueToRowIndexes = new HashMap<>();

        for (int rowIndex = 0; rowIndex < normalizedRows.size(); rowIndex++) {
            List<String> normalizedRow = normalizedRows.get(rowIndex);

            String firstCellValue = normalizedRow.get(0);

            firstCellValueToRowIndexes
                    .computeIfAbsent(firstCellValue, key -> new ArrayList<>())
                    .add(rowIndex);
        }

        return firstCellValueToRowIndexes;
    }

    private Map<Integer, List<Integer>> collectDuplicateRowGroups(List<List<String>> normalizedRows, Map<String, List<Integer>> firstCellValueToRowIndexes) {
        AtomicBoolean[] visitedRowFlags = IntStream.range(0, normalizedRows.size())
                .mapToObj(i -> new AtomicBoolean(false))
                .toArray(AtomicBoolean[]::new);

        Map<Integer, List<Integer>> representativeRowIndexToDuplicates = new LinkedHashMap<>();

        for (List<Integer> candidateRowIndexes : firstCellValueToRowIndexes.values()) {
            if (candidateRowIndexes.size() < 2) {
                continue;
            }

            List<String> mergedGroupRows = candidateRowIndexes.stream()
                    .map(normalizedRows::get)
                    .map(cells -> String.join("|", cells))
                    .collect(Collectors.toList());

            List<List<Integer>> similarityGraphs = similarityGraphBuilder.buildSimilarityGraphs(mergedGroupRows);

            for (int graphNodeIndex = 0; graphNodeIndex < candidateRowIndexes.size(); graphNodeIndex++) {
                int globalRowIndex = candidateRowIndexes.get(graphNodeIndex);

                if (visitedRowFlags[globalRowIndex].get()) {
                    continue;
                }

                List<Integer> duplicateCluster = depthFirstSearch(graphNodeIndex, similarityGraphs, candidateRowIndexes, visitedRowFlags);

                if (duplicateCluster.size() > 1) {
                    duplicateCluster.sort(Integer::compare);
                    representativeRowIndexToDuplicates.put(duplicateCluster.get(0), duplicateCluster);
                }
            }

        }

        return representativeRowIndexToDuplicates;
    }

    private List<Integer> depthFirstSearch(int start, List<List<Integer>> graph, List<Integer> group, AtomicBoolean[] visited) {
        List<Integer> result = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);

        while (!stack.isEmpty()) {
            int local = stack.pop();
            int global = group.get(local);

            if (visited[global].getAndSet(true)) {
                continue;
            }

            result.add(global);

            for (int neighbor : graph.get(local)) {
                int neighborGlobal = group.get(neighbor);
                if (!visited[neighborGlobal].get()) {
                    stack.push(neighbor);
                }
            }
        }

        return result;
    }

    private List<DuplicateMatchResponse> buildResponses(List<List<String>> normalizedRows, Map<Integer, List<Integer>> groups) {
        return groups.entrySet().stream().map(entry -> {
            int origin = entry.getKey();
            List<Integer> others = new ArrayList<>(entry.getValue());
            others.remove(Integer.valueOf(origin));

            return new DuplicateMatchResponse(normalizedRows.get(origin), origin, others);
        }).toList();
    }
}

