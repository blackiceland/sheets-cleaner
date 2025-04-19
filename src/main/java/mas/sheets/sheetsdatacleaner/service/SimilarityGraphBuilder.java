package mas.sheets.sheetsdatacleaner.service;

import java.util.List;

public interface SimilarityGraphBuilder {

    List<List<Integer>> buildSimilarityGraphs(List<String> mergedRows);

}
