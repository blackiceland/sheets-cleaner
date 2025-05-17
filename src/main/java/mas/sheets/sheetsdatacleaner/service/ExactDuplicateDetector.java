package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowNorm;

import java.util.List;

public interface ExactDuplicateDetector {

    ExactDetectionResult detect(List<RowNorm> rows);

}
