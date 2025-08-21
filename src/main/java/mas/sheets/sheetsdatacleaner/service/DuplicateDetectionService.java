package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowNorm;

import java.util.List;

public interface DuplicateDetectionService {

    DuplicateMatchResponse detectFuzzy(List<RowNorm> normalized, ExactDetectionResult exact);

}
