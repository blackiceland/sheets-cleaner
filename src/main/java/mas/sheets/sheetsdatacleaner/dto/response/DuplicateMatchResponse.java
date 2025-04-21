package mas.sheets.sheetsdatacleaner.dto.response;

import java.util.List;

public record DuplicateMatchResponse(List<DuplicateGroup> highConfidenceGroups, List<DuplicateGroup> mediumConfidenceGroups) {
}
