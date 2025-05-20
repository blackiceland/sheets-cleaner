package mas.sheets.sheetsdatacleaner.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;

@Configuration
public class EmbeddingHttpConfig {

    @Bean
    public HttpClient embeddingHttpClient(@Qualifier("embeddingExecutor") ExecutorService executor) {
        return HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

