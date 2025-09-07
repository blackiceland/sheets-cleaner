package mas.sheets.sheetsdatacleaner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import mas.sheets.sheetsdatacleaner.model.RowNorm;

import java.io.Serializable;
import java.util.List;

public record DatasetContext(
        @JsonProperty("schemaVersion") int schemaVersion,
        @JsonProperty("rows") List<RowNorm> rows
) implements Serializable {
}



