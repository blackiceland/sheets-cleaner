package mas.sheets.sheetsdatacleaner.config;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration
public class EmbeddingConcurrencyConfig {

    @Bean(name = "embeddingExecutor", destroyMethod = "shutdown")
    public ExecutorService embeddingExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        int poolSize = Math.max(4, cores);

        ThreadFactory factory = new ThreadFactoryBuilder()
                .setNameFormat("embedding-%d")
                .setDaemon(true)
                .build();

        return Executors.newFixedThreadPool(poolSize, factory);
    }
}
