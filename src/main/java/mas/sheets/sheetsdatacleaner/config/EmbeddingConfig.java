package mas.sheets.sheetsdatacleaner.config;

//import com.fasterxml.jackson.databind.ObjectMapper;
//import mas.sheets.sheetsdatacleaner.client.EmbeddingApiClient;
//import mas.sheets.sheetsdatacleaner.config.properties.EmbeddingApiProperties;
//import org.springframework.boot.context.properties.EnableConfigurationProperties;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.util.concurrent.ExecutorService;

//@Configuration
//@EnableConfigurationProperties(EmbeddingApiProperties.class)
//public class EmbeddingConfig {
//
//    @Bean
//    public EmbeddingApiClient embeddingApiClient(ObjectMapper mapper,
//                                                 HttpClient http,
//                                                 ExecutorService embeddingExecutor,
//                                                 EmbeddingApiProperties props) {
//        URI apiUri = URI.create(props.getUrl());
//
//        return new EmbeddingApiClient(mapper, http, apiUri, embeddingExecutor);
//    }
//}
