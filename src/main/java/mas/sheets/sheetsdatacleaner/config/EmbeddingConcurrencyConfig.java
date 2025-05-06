package mas.sheets.sheetsdatacleaner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class EmbeddingConcurrencyConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService embeddingExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Executors.newFixedThreadPool(Math.max(4, cores));
    }

}