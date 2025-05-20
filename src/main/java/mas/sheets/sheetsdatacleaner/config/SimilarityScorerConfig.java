package mas.sheets.sheetsdatacleaner.config;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.JaroWinklerScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SimilarityScorerConfig {

    @Bean
    @Qualifier("heuristicScorers")
    public List<SimilarityScorer> heuristicScorers(TokenSetRatioScorer tokenSet, LevenshteinScorer levenshtein, JaroWinklerScorer jaroWinklerScorer) {
        return List.of(tokenSet, levenshtein, jaroWinklerScorer);
    }
}

