package mas.sheets.sheetsdatacleaner.config;

import mas.sheets.sheetsdatacleaner.dto.DatasetContext;
import mas.sheets.sheetsdatacleaner.service.DatasetContextStore;
import mas.sheets.sheetsdatacleaner.service.impl.CaffeineDatasetContextStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ContextStoreConfig {

    @Bean
    @ConditionalOnProperty(prefix = "context", name = "store", havingValue = "caffeine", matchIfMissing = true)
    public DatasetContextStore<DatasetContext> caffeineStore(ContextProperties props) {
        return new CaffeineDatasetContextStore<>(props);
    }
}


