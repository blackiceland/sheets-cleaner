package mas.sheets.sheetsdatacleaner.controller;

import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;

import java.io.InputStream;

public interface DuplicateController {

    DuplicateMatchResponse detectDuplicates(InputStream request);

}
