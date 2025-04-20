package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

@Component
public class JaroWinklerScorer implements SimilarityScorer {

    private static final JaroWinklerSimilarity similarity = new JaroWinklerSimilarity();
    private static final String STRUCTURED_CHAR_PATTERN = "[\\s\\-.:_/]";
    private static final String DATE_PATTERN = "\\d{1,4}[-./]\\d{1,2}[-./]\\d{1,4}";

    @Override
    public double calculateScore(String left, String right) {
        String cleanLeft = sanitize(left);
        String cleanRight = sanitize(right);

        if (cleanLeft.isEmpty() || cleanRight.isEmpty()) {
            return 0.0;
        }

        if (isStructuredData(cleanLeft) || isStructuredData(cleanRight)) {
            return 0.0;
        }

        return similarity.apply(cleanLeft, cleanRight);
    }

    private String sanitize(String input) {
        return input == null ? "" : input.replace("|", "").trim();
    }

    private boolean isStructuredData(String input) {
        String compact = input.replaceAll(STRUCTURED_CHAR_PATTERN, "");
        boolean mostlyDigits = compact.length() > 3 && compact.chars().filter(Character::isDigit).count() > compact.length() * 0.7;
        boolean dateLike = input.matches(DATE_PATTERN);

        return mostlyDigits || dateLike;
    }
}
