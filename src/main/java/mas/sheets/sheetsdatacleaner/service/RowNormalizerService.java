package mas.sheets.sheetsdatacleaner.service;

import java.util.List;

public interface RowNormalizerService {

    List<String> normalizeRows(List<List<String>> rows);

}
