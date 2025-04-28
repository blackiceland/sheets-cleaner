package mas.sheets.sheetsdatacleaner.dto.response;

import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import java.util.Set;

public record DuplicateMatchResponse( Set<IndexPair<Integer, Integer>> confirmed) {
}
