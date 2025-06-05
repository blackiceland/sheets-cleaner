package mas.sheets.sheetsdatacleaner.dto.response;

import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowMeta;

import java.util.List;
import java.util.Set;

public record DuplicateMatchResponse(
        Set<IndexPair> confirmed,
        Set<IndexPair> candidates,
        List<RowMeta> meta
) {}