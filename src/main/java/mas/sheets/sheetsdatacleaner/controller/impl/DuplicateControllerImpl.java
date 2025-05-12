package mas.sheets.sheetsdatacleaner.controller.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.controller.DuplicateController;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static mas.sheets.sheetsdatacleaner.util.CleanerUtils.DUPLICATES;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;

    @Override
    @PostMapping(
            value = DUPLICATES,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DuplicateMatchResponse detectDuplicates(@RequestBody List<List<String>> rows) {
        log.info("Detecting duplicates for {} rows", rows.size());
        return duplicateDetectionService.findDuplicates(new DuplicateMatchRequest(rows));
    }
}
