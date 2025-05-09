package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.model.IndexPair;

import java.util.List;
import java.util.Set;

public interface MinHashCandidateDetectionService {

    Set<IndexPair> generateCandidatePairs(List<String> normalizedRows);

}
