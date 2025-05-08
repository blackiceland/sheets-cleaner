package mas.sheets.sheetsdatacleaner.config;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;


@Configuration
public class HttpMetricsTagsConfiguration {

    @Bean
    public ServerRequestObservationConvention operationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public @NotNull KeyValues getLowCardinalityKeyValues(@NotNull ServerRequestObservationContext context) {
                KeyValues base = super.getLowCardinalityKeyValues(context);
                String op = context.getCarrier().getHeader("X-Operation");
                return base.and(KeyValue.of("operation", op != null ? op : "unknown"));
            }
        };
    }
}

