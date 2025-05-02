package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import org.springframework.stereotype.Component;

@Component
public class LevenshteinScorer implements SimilarityScorer {

    private static final double SIMILARITY_THRESHOLD = 0.3;

    @Override
    public double calculateScore(String left, String right) {
        String cleanLeft = sanitizeInput(left);
        String cleanRight = sanitizeInput(right);

        if (cleanLeft.isEmpty() || cleanRight.isEmpty()) {
            return 0.0;
        }

        int maximumLength = Math.max(cleanLeft.length(), cleanRight.length());
        int maxAllowedDistance = (int) Math.ceil(maximumLength * (1.0 - SIMILARITY_THRESHOLD));
        int editDistance = calculateLevenshteinDistanceWithThreshold(cleanLeft, cleanRight, maxAllowedDistance);

        if (editDistance == -1) {
            return 0.0;
        }

        if (maximumLength == 0) {
            return 1.0;
        }

        return 1.0 - ((double) editDistance / maximumLength);
    }

    private String sanitizeInput(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        return input.replace("|", "").trim();
    }

    private int calculateLevenshteinDistanceWithThreshold(String left, String right, int threshold) {
        int leftLength = left.length();
        int rightLength = right.length();

        if (Math.abs(leftLength - rightLength) > threshold) {
            return -1;
        }

        int[] previousRow = new int[rightLength + 1];
        int[] currentRow = new int[rightLength + 1];

        for (int j = 0; j <= rightLength; j++) {
            previousRow[j] = j;
        }

        for (int i = 1; i <= leftLength; i++) {
            currentRow[0] = i;
            int minValue = i;

            for (int j = 1; j <= rightLength; j++) {
                char leftChar = left.charAt(i - 1);
                char rightChar = right.charAt(j - 1);

                int substitutionCost = (leftChar == rightChar) ? 0 : 1;

                int insertion = currentRow[j - 1] + 1;
                int deletion = previousRow[j] + 1;
                int substitution = previousRow[j - 1] + substitutionCost;

                currentRow[j] = Math.min(Math.min(insertion, deletion), substitution);
                minValue = Math.min(minValue, currentRow[j]);
            }

            if (minValue > threshold) {
                return -1;
            }

            int[] tempRow = previousRow;
            previousRow = currentRow;
            currentRow = tempRow;
        }

        return previousRow[rightLength];
    }
}
