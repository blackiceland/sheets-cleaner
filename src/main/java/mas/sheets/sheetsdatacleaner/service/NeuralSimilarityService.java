package mas.sheets.sheetsdatacleaner.service;

import org.apache.commons.lang3.tuple.Pair;

import java.util.List;
import java.util.Map;

public interface NeuralSimilarityService {

    double fetchSimilarityScore(String left, String right);

    Map<Pair<String, String>, Double> fetchBatchSimilarityScores(List<Pair<String, String>> pairs);

}
