package mas.sheets.sheetsdatacleaner.controller.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.controller.DuplicateController;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.dto.request.FuzzyDuplicateRequest;
import mas.sheets.sheetsdatacleaner.dto.response.ExactDuplicateResponse;
import mas.sheets.sheetsdatacleaner.dto.DatasetPayload;
import mas.sheets.sheetsdatacleaner.dto.ExactStageResult;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateService;
import mas.sheets.sheetsdatacleaner.util.DatasetTokenUtil;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Instant;

@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;
    private final ExactDuplicateService exactService;
    private final ExactDuplicateDetector exactDetector;

    @Value("${token.secret:0123456789abcdef0123456789abcdef}")
    private byte[] tokenSecret;

    @Override
    @PostMapping("/api/v1/sheets/duplicates")
    public DuplicateMatchResponse detectDuplicates(@RequestBody DuplicateMatchRequest request) {
        return null;
    }

    @PostMapping("/api/v1/sheets/duplicates/exact")
    public ExactDuplicateResponse exactStage(@RequestBody DuplicateMatchRequest request) {
        ExactStageResult stage = exactService.detectExact(request);

        DuplicateMatchResponse resp = stage.exactResponse();

        long exp = Instant.now().getEpochSecond() + 1800;

        DatasetPayload payload = new DatasetPayload(exp, stage.normalizedRows(), stage.exactResult(), false);

        String token = DatasetTokenUtil.encode(payload, tokenSecret);

        List<ExactDuplicateResponse.RowIndexId> rowsInfo = stage.normalizedRows().stream()
                .map(r -> new ExactDuplicateResponse.RowIndexId(
                        r.idx(),
                        r.rowId()))
                .toList();

        return new ExactDuplicateResponse(token, resp, rowsInfo);
    }

    @PostMapping("/api/v1/sheets/duplicates/fuzzy")
    public DuplicateMatchResponse fuzzyStage(@RequestBody FuzzyDuplicateRequest request) {
        DatasetPayload payload = DatasetTokenUtil.decode(request.datasetToken(), tokenSecret);

        List<Long> removed = request.removedRowIds() == null
                ? List.of()
                : request.removedRowIds();

        List<RowNorm> kept = payload.rows()
                .stream()
                .filter(r -> !removed.contains(r.rowId()))
                .toList();

        ExactDetectionResult exact = exactDetector.detect(kept);

        return duplicateDetectionService.detectFuzzy(kept, exact);
    }
}
