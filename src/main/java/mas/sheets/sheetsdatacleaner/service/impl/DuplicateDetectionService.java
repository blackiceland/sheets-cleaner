package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;

import java.util.List;

public interface DuplicateDetectionService {

    List<DuplicateMatchResponse> findDuplicates(DuplicateMatchRequest request);

}
