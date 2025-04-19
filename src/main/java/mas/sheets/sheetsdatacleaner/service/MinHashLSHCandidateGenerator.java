package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashLSHCandidateGeneratorImpl;

import java.util.List;
import java.util.Set;

public interface MinHashLSHCandidateGenerator {

    Set<MinHashLSHCandidateGeneratorImpl.IndexPair<Integer, Integer>> generateCandidatePairs(List<String> normalizedRows);

}
