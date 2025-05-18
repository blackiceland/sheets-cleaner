package mas.sheets.sheetsdatacleaner.controller.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.controller.DuplicateController;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static mas.sheets.sheetsdatacleaner.util.CleanerUtils.DUPLICATES;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;

    @Override
    @PostMapping(value = DUPLICATES)
    public DuplicateMatchResponse detectDuplicates(@RequestBody DuplicateMatchRequest request) {
        log.info("Detecting duplicates for {} rows", request.rows());

        return duplicateDetectionService.findDuplicates(request);
    }
}
