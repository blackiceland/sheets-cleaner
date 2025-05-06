package mas.sheets.sheetsdatacleaner.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "embedding.api")
@Getter
@Setter
public class EmbeddingApiProperties {
    private String url;
}
