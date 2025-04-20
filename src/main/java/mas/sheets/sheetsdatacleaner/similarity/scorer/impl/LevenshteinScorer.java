package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import org.springframework.stereotype.Component;

@Component
public class LevenshteinScorer implements SimilarityScorer {

    @Override
    public double calculateScore(String left, String right) {
        String cleanLeft = sanitizeInput(left);
        String cleanRight = sanitizeInput(right);

        if (cleanLeft.isEmpty() || cleanRight.isEmpty()) {
            return 0.0;
        }

        int editDistance = calculateLevenshteinDistance(cleanLeft, cleanRight);
        int maximumLength = Math.max(cleanLeft.length(), cleanRight.length());

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

    private int calculateLevenshteinDistance(String left, String right) {
        int leftLength = left.length();
        int rightLength = right.length();

        int[][] distanceMatrix = new int[leftLength + 1][rightLength + 1];

        for (int row = 0; row <= leftLength; row++) {
            distanceMatrix[row][0] = row;
        }

        for (int column = 0; column <= rightLength; column++) {
            distanceMatrix[0][column] = column;
        }

        for (int row = 1; row <= leftLength; row++) {
            for (int column = 1; column <= rightLength; column++) {
                char leftChar = left.charAt(row - 1);
                char rightChar = right.charAt(column - 1);

                int substitutionCost = leftChar == rightChar ? 0 : 1;

                int insertion = distanceMatrix[row][column - 1] + 1;
                int deletion = distanceMatrix[row - 1][column] + 1;
                int substitution = distanceMatrix[row - 1][column - 1] + substitutionCost;

                distanceMatrix[row][column] = Math.min(Math.min(insertion, deletion), substitution);
            }
        }

        return distanceMatrix[leftLength][rightLength];
    }
}
