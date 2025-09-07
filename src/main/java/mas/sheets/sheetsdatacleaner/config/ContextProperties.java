package mas.sheets.sheetsdatacleaner.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "context")
public class ContextProperties {

    private int ttlSeconds = 300;
    private int maxBytes = 1048576;
}


