package mas.sheets.sheetsdatacleaner.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    @Bean("workPool")
    ThreadPoolTaskExecutor workPool(MeterRegistry registry) {
        int cpu  = Runtime.getRuntime().availableProcessors();
        int core = Math.min(cpu, 4);
        int max  = cpu * 2;

        Counter rejects = Counter.builder("rejections_total").register(registry);

        ThreadPoolTaskExecutor t = new ThreadPoolTaskExecutor();
        t.setCorePoolSize(core);
        t.setMaxPoolSize(max);
        t.setQueueCapacity(400);
        t.setThreadNamePrefix("work-");
        t.setRejectedExecutionHandler((r, ex) -> {
            rejects.increment();
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(r, ex);
        });
        t.initialize();
        return t;
    }
}