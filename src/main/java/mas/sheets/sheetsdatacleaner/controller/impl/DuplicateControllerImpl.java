package mas.sheets.sheetsdatacleaner.controller.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.controller.DuplicateController;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.parser.RowJsonStreamParser;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;

import static mas.sheets.sheetsdatacleaner.util.CleanerUtils.DUPLICATES;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;
    private final RowJsonStreamParser rowJsonStreamParser;

    @Override
    @PostMapping(DUPLICATES)
    public DuplicateMatchResponse detectDuplicates(InputStream request) {
        List<List<String>> rows = rowJsonStreamParser.parse(request);
        log.info("Detecting duplicates for {} rows", rows.size());

        return duplicateDetectionService.findDuplicates(new DuplicateMatchRequest(rows));
    }
}