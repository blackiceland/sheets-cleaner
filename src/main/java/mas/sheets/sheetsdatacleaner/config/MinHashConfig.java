package mas.sheets.sheetsdatacleaner.config;

import mas.sheets.sheetsdatacleaner.config.properties.MinHashProps;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinHashProps.class)
class MinHashConfig {}
