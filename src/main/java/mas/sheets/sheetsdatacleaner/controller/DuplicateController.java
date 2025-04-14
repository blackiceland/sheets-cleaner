package mas.sheets.sheetsdatacleaner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static mas.sheets.sheetsdatacleaner.util.CleanerUtils.DUPLICATES;

@RestController
@RequiredArgsConstructor
@Slf4j
public class DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;


    @PostMapping(DUPLICATES)
    public ResponseEntity<List<DuplicateMatchResponse>> detectDuplicates(@RequestBody DuplicateMatchRequest request) {
        List<DuplicateMatchResponse> duplicates = duplicateDetectionService.findDuplicates(request);

        return ResponseEntity.ok(duplicates);
    }
}
