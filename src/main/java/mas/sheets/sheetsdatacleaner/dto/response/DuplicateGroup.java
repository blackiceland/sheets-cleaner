package mas.sheets.sheetsdatacleaner.dto.response;

import mas.sheets.sheetsdatacleaner.enums.MatchConfidenceLevel;

import java.util.List;

public record DuplicateGroup(int baseIndex, List<Integer> duplicateIndexes, MatchConfidenceLevel level) {}
