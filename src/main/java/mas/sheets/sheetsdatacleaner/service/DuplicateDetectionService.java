package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;

import java.util.List;

public interface DuplicateDetectionService {

    DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request);

}
