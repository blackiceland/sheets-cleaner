package mas.sheets.sheetsdatacleaner.configuration;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.JaroWinklerScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

@Configuration
public class SimilarityScorerConfig {

    @Bean
    @Qualifier("heuristicScorers")
    public List<SimilarityScorer> heuristicScorers(TokenSetRatioScorer tokenSet, LevenshteinScorer levenshtein, JaroWinklerScorer jaroWinklerScorer) {
        return List.of(tokenSet, levenshtein, jaroWinklerScorer);
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }

    @Bean
    public URI similarityApiUri() {
        return URI.create("http://localhost:5000/similarity");
    }
}

