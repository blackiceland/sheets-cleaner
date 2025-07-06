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
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateService;
import mas.sheets.sheetsdatacleaner.util.DatasetTokenUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Instant;

@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;
    private final ExactDuplicateService exactService;

    @Value("${token.secret}")
    private byte[] tokenSecret;

    @Override
    @PostMapping("/api/v1/sheets/duplicates")
    public DuplicateMatchResponse detectDuplicates(@RequestBody DuplicateMatchRequest request) {

        List<List<String>> rows = request.rows();
        boolean hasHeader = request.hasHeaders();

        DuplicateMatchRequest reqForService = hasHeader && !rows.isEmpty()
                ? new DuplicateMatchRequest(rows.subList(1, rows.size()), false)
                : request;

        log.info("Detecting duplicates for {} rows", reqForService.rows().size());

        DuplicateMatchResponse resp = duplicateDetectionService.findDuplicates(reqForService);

        if (hasHeader) {
            Set<IndexPair> confirmedShift = resp.confirmed().stream()
                    .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
                    .collect(Collectors.toSet());

            Set<IndexPair> candidatesShift = resp.candidates().stream()
                    .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
                    .collect(Collectors.toSet());

            List<RowMeta> metaShift = resp.meta().stream()
                    .map(m -> new RowMeta(m.idx() + 1, m.clusterId(), m.kind()))
                    .toList();

            resp = new DuplicateMatchResponse(confirmedShift, candidatesShift, metaShift);
        }

        return resp;
    }

    @PostMapping("/api/v1/sheets/duplicates/exact")
    public ExactDuplicateResponse exactStage(@RequestBody DuplicateMatchRequest request) {

        List<List<String>> rows = request.rows();
        boolean hasHeader = request.hasHeaders();

        DuplicateMatchRequest req = (hasHeader && !rows.isEmpty())
                ? new DuplicateMatchRequest(rows.subList(1, rows.size()), false)
                : request;

        ExactStageResult stage = exactService.detectExact(req);

        DuplicateMatchResponse resp = stage.exactResponse();

        if (hasHeader) {
            Set<IndexPair> confirmedShift = resp.confirmed().stream()
                    .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
                    .collect(Collectors.toSet());

            Set<IndexPair> candidatesShift = resp.candidates().stream()
                    .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
                    .collect(Collectors.toSet());

            List<RowMeta> metaShift = resp.meta().stream()
                    .map(m -> new RowMeta(m.idx() + 1, m.clusterId(), m.kind()))
                    .toList();

            resp = new DuplicateMatchResponse(confirmedShift, candidatesShift, metaShift);
        }

        long exp = Instant.now().getEpochSecond() + 1800;
        DatasetPayload payload = new DatasetPayload(exp, stage.normalizedRows(), stage.exactResult(), hasHeader);

        String token = DatasetTokenUtil.encode(payload, tokenSecret);

        return new ExactDuplicateResponse(token, resp);
    }

    @PostMapping("/api/v1/sheets/duplicates/fuzzy")
    public DuplicateMatchResponse fuzzyStage(@RequestBody FuzzyDuplicateRequest request) {
        DatasetPayload payload = DatasetTokenUtil.decode(request.datasetToken(), tokenSecret);

        // TODO: removedRowIds handling later

        DuplicateMatchResponse resp = duplicateDetectionService.detectFuzzy(payload.rows(), payload.exact());

        if (payload.hasHeader()) {
            Set<IndexPair> confirmedShift = resp.confirmed().stream()
                    .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
                    .collect(Collectors.toSet());

            Set<IndexPair> candidatesShift = resp.candidates().stream()
                    .map(p -> IndexPair.of(p.first() + 1, p.second() + 1))
                    .collect(Collectors.toSet());

            List<RowMeta> metaShift = resp.meta().stream()
                    .map(m -> new RowMeta(m.idx() + 1, m.clusterId(), m.kind()))
                    .toList();

            resp = new DuplicateMatchResponse(confirmedShift, candidatesShift, metaShift);
        }

        return resp;
    }
}
