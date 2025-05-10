package mas.sheets.sheetsdatacleaner.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "duplicate")
@Data
public class DuplicateProperties {
    private int minSimilarity;
    private int batchSize;
}