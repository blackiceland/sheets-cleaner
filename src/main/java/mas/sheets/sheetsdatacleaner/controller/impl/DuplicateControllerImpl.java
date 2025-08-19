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

        // List<List<String>> rows = request.rows();
        // boolean hasHeader = request.hasHeaders();
        // DuplicateMatchRequest reqForService = hasHeader && !rows.isEmpty()
        //         ? new DuplicateMatchRequest(rows.subList(1, rows.size()), false)
        //         : request;
        DuplicateMatchRequest reqForService = request;

        log.info("Detecting duplicates for {} rows", reqForService.rows().size());

        DuplicateMatchResponse resp = duplicateDetectionService.findDuplicates(reqForService);

        // if (hasHeader) {
        //     Set<IndexPair> confirmedShift = resp.confirmed().stream()
        //             .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
        //             .collect(Collectors.toSet());
        //
        //     Set<IndexPair> candidatesShift = resp.candidates().stream()
        //             .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
        //             .collect(Collectors.toSet());
        //
        //     List<RowMeta> metaShift = resp.meta().stream()
        //             .map(m -> new RowMeta(m.idx() + 1, m.clusterId(), m.kind()))
        //             .toList();
        //
        //     resp = new DuplicateMatchResponse(confirmedShift, candidatesShift, metaShift);
        // }

        return resp;
    }

    @PostMapping("/api/v1/sheets/duplicates/exact")
    public ExactDuplicateResponse exactStage(@RequestBody DuplicateMatchRequest request) {

        // List<List<String>> rows = request.rows();
        // boolean hasHeader = request.hasHeaders();
        // DuplicateMatchRequest req = (hasHeader && !rows.isEmpty())
        //         ? new DuplicateMatchRequest(rows.subList(1, rows.size()), false)
        //         : request;
        DuplicateMatchRequest req = request;

        ExactStageResult stage = exactService.detectExact(req);

        DuplicateMatchResponse resp = stage.exactResponse();

        // if (hasHeader) {
        //     Set<IndexPair> confirmedShift = resp.confirmed().stream()
        //             .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
        //             .collect(Collectors.toSet());
        //
        //     Set<IndexPair> candidatesShift = resp.candidates().stream()
        //             .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
        //             .collect(Collectors.toSet());
        //
        //     List<RowMeta> metaShift = resp.meta().stream()
        //             .map(m -> new RowMeta(m.idx() + 1, m.clusterId(), m.kind()))
        //             .toList();
        //
        //     resp = new DuplicateMatchResponse(confirmedShift, candidatesShift, metaShift);
        // }

        long exp = Instant.now().getEpochSecond() + 1800;
        // DatasetPayload payload = new DatasetPayload(exp, stage.normalizedRows(), stage.exactResult(), hasHeader);
        DatasetPayload payload = new DatasetPayload(exp, stage.normalizedRows(), stage.exactResult(), false);

        String token = DatasetTokenUtil.encode(payload, tokenSecret);

        List<ExactDuplicateResponse.RowIndexId> rowsInfo = stage.normalizedRows().stream()
                .map(r -> new ExactDuplicateResponse.RowIndexId(
                        // hasHeader ? r.idx() + 1 : r.idx(),
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

        DuplicateMatchResponse resp = duplicateDetectionService.detectFuzzy(kept, exact);

        // if (payload.hasHeader()) {
        //     Set<IndexPair> confirmedShift = resp.confirmed()
        //             .stream()
        //             .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
        //             .collect(Collectors.toSet());
        //
        //     Set<IndexPair> candidatesShift = resp.candidates()
        //             .stream()
        //             .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
        //             .collect(Collectors.toSet());
        //
        //     List<RowMeta> metaShift = resp.meta().stream()
        //             .map(m -> new RowMeta(m.idx() + 1, m.clusterId(), m.kind()))
        //             .toList();
        //
        //     resp = new DuplicateMatchResponse(confirmedShift, candidatesShift, metaShift);
        // }

        return resp;
    }
}
