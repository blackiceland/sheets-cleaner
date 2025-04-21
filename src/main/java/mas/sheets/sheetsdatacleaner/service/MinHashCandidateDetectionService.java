package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl;

import java.util.List;
import java.util.Set;

public interface MinHashCandidateDetectionService {

    Set<MinHashCandidateDetectionServiceImpl.IndexPair<Integer, Integer>> generateCandidatePairs(List<String> normalizedRows);

}
