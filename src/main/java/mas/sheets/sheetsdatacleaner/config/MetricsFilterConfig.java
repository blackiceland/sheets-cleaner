package mas.sheets.sheetsdatacleaner.config;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsFilterConfig {

    @Bean
    public MeterFilter ignoreTechnicalUris() {
        return MeterFilter.deny(id -> {
            String uri = id.getTag("uri");

            if (uri == null) {
                return false;
            }

            return uri.startsWith("/actuator") || uri.equals("/**");
        });
    }
}