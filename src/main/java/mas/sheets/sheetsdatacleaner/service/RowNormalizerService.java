package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.model.RowNorm;

import java.util.List;

public interface RowNormalizerService {

    List<RowNorm> normalizeRows(List<List<String>> rows);

}
