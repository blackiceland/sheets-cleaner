package mas.sheets.sheetsdatacleaner.service.type_detector;

import mas.sheets.sheetsdatacleaner.enums.DataType;

public interface DataTypeDetector {
    DataType detect(String raw);
}
