package mas.sheets.sheetsdatacleaner.controller.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.controller.DuplicateController;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;

    @Override
    @PostMapping("/api/v1/sheets/duplicates")
    public DuplicateMatchResponse detectDuplicates(@RequestBody DuplicateMatchRequest request) {
        List<List<String>> rows = request.rows();

        if (request.hasHeaders() && !rows.isEmpty()) {
            rows = rows.subList(1, rows.size());
        }

        DuplicateMatchRequest trimmedRequest = new DuplicateMatchRequest(rows, false);
        log.info("Detecting duplicates for {} rows", rows.size());

        return duplicateDetectionService.findDuplicates(trimmedRequest);
    }
}
