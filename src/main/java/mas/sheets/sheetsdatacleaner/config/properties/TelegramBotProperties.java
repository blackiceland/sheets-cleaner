package mas.sheets.sheetsdatacleaner.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram.bot")
@Data
public class TelegramBotProperties {
    private String token;
}
