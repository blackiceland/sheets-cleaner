package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.ExactStageResult;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateService;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExactDuplicateServiceImpl implements ExactDuplicateService {

    private final RowNormalizerService normalizer;
    private final ExactDuplicateDetector exactDetector;

    @Override
    public ExactStageResult detectExact(DuplicateMatchRequest request) {
        List<RowNorm> normalized = normalizer.normalizeRows(request.rows());
        log.info("[exact] normalized rows={}", normalized.size());

        ExactDetectionResult exact = exactDetector.detect(normalized);
        log.info("[exact] groups={} duplicates={} remaining={}",
                exact.duplicateGroups().size(),
                exact.duplicateGroups().stream().mapToInt(List::size).sum(),
                exact.remainRows().size());

        Set<IndexPair> confirmed = new HashSet<>();
        for (List<Integer> g : exact.duplicateGroups())
            for (int i = 0; i < g.size(); i++)
                for (int j = i + 1; j < g.size(); j++)
                    confirmed.add(IndexPair.of(g.get(i), g.get(j)));

        List<RowMeta> meta = new ArrayList<>(exact.metaByIdx().values());

        DuplicateMatchResponse resp = new DuplicateMatchResponse(confirmed, Collections.emptySet(), meta);

        return new ExactStageResult(resp, normalized, exact);
    }
} 