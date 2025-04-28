package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.service.impl.ExactDuplicateDetectorImpl;

import java.util.List;

public interface ExactDuplicateDetector {

    ExactDuplicateDetectorImpl.ExactDetectionResult detect(List<String> normalizedRows);

}
