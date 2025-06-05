package mas.sheets.sheetsdatacleaner.model;

import mas.sheets.sheetsdatacleaner.enums.ClusterKind;

import java.util.UUID;

public record RowMeta(int idx, UUID clusterId, ClusterKind kind) {}