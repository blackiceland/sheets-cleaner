package mas.sheets.sheetsdatacleaner.config;

import mas.sheets.sheetsdatacleaner.config.properties.DuplicateDetectorProps;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DuplicateDetectorProps.class)
class DuplicateDetectorConfig {}
