package mas.sheets.sheetsdatacleaner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class EmbeddingConfig {

    @Bean
    public URI embeddingApiUri(@Value("${embedding.api.base-url}") String base) {
        return URI.create(base);
    }
}
