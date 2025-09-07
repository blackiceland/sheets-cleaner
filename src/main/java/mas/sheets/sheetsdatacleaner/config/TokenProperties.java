package mas.sheets.sheetsdatacleaner.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "token")
public class TokenProperties {

    private String secret;
}



