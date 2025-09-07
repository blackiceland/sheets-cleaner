package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.DatasetContext;

public interface DatasetContextService {

    String saveContext(DatasetContext context);

    DatasetContext loadContextOrLegacy(String tokenOrId);
}



