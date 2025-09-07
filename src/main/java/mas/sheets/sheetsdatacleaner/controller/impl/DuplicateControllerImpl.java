package mas.sheets.sheetsdatacleaner.controller.impl;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.controller.DuplicateController;
import mas.sheets.sheetsdatacleaner.dto.DatasetContext;
import mas.sheets.sheetsdatacleaner.dto.ExactStageResult;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.request.FuzzyDuplicateRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.dto.response.ExactDuplicateResponse;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.DatasetContextService;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateService;
import mas.sheets.sheetsdatacleaner.util.ApiPaths;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Дубликаты", description = "Операции поиска точных и нечетких дубликатов")
public class DuplicateControllerImpl implements DuplicateController {

    private final DuplicateDetectionService duplicateDetectionService;
    private final ExactDuplicateService exactService;
    private final ExactDuplicateDetector exactDetector;
    private final DatasetContextService datasetContextService;


    @PostMapping(ApiPaths.DUPLICATES_EXACT)
    @Operation(
            summary = "Поиск точных дубликатов",
            description = "Выполняет этап точного поиска дубликатов и возвращает идентификатор контекста (UUID) для последующего этапа нечеткого поиска",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Обработка завершена",
                            content = @Content(schema = @Schema(implementation = ExactDuplicateResponse.class)))
            }
    )
    public ExactDuplicateResponse detectExactDuplicates(@RequestBody DuplicateMatchRequest request) {
        ExactStageResult exactStageResult = exactService.detectExact(request);

        DuplicateMatchResponse exactResponse = exactStageResult.exactResponse();

        DatasetContext context = new DatasetContext(1, exactStageResult.normalizedRows());

        String contextId = datasetContextService.saveContext(context);

        List<ExactDuplicateResponse.RowIndexId> rowsInfo = exactStageResult.normalizedRows()
                .stream()
                .map(row -> new ExactDuplicateResponse.RowIndexId(row.idx(), row.rowId()))
                .toList();

        return new ExactDuplicateResponse(contextId, exactResponse, rowsInfo);
    }

    @PostMapping(ApiPaths.DUPLICATES_FUZZY)
    @Operation(
            summary = "Поиск нечетких дубликатов",
            description = "Выполняет этап нечеткого поиска дубликатов, используя идентификатор контекста (UUID) из предыдущего шага",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Обработка завершена",
                            content = @Content(schema = @Schema(implementation = DuplicateMatchResponse.class)))
            }
    )
    public DuplicateMatchResponse detectFuzzyDuplicates(@RequestBody FuzzyDuplicateRequest request) {
        DatasetContext context = datasetContextService.loadContextOrLegacy(request.datasetToken());

        List<Long> removedRowIds = request.removedRowIds() == null
                ? List.of()
                : request.removedRowIds();

        List<RowNorm> keptRows = context.rows()
                .stream()
                .filter(row -> !removedRowIds.contains(row.rowId()))
                .toList();

        ExactDetectionResult exactDetectionResult = exactDetector.detect(keptRows);

        return duplicateDetectionService.detectFuzzy(keptRows, exactDetectionResult);
    }
}
