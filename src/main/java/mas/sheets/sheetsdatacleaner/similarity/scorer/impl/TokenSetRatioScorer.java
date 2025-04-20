package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TokenSetRatioScorer implements SimilarityScorer {

    @Override
    public double calculateScore(String left, String right) {
        Set<String> leftTokens = tokenize(left);
        Set<String> rightTokens = tokenize(right);

        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }

        Set<String> sharedTokens = new HashSet<>(leftTokens);
        sharedTokens.retainAll(rightTokens);

        if (sharedTokens.isEmpty()) {
            return 0.0;
        }

        double precision = (double) sharedTokens.size() / leftTokens.size();
        double recall = (double) sharedTokens.size() / rightTokens.size();

        double denominator = precision + recall;

        if (denominator == 0.0) {
            return 0.0;
        }

        return 2 * precision * recall / denominator;
    }

    private Set<String> tokenize(String input) {
        if (input == null || input.isBlank()) {
            return Set.of();
        }

        return Arrays.stream(input.split("\\s+"))
                .filter(token -> !token.isBlank() && !token.equals("|"))
                .collect(Collectors.toSet());
    }
}

