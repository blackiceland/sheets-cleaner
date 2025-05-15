package mas.sheets.sheetsdatacleaner.model;

import mas.sheets.sheetsdatacleaner.enums.DataType;

public record NormalizedRow(String text, DataType type) {}
