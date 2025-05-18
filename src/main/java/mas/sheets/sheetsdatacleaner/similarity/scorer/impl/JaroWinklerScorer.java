package mas.sheets.sheetsdatacleaner.similarity.scorer.impl;

import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import org.springframework.stereotype.Component;

@Component
public class JaroWinklerScorer implements SimilarityScorer {

    private static final double PREFIX_SCALE = 0.1;
    private static final double MAX_LEN_DIFF_RATIO = 0.5;

    @Override
    public double calculateScore(String s1, String s2) {
        if (s1 == null && s2 == null) {
            return 1.0;
        }

        if (s1 == null || s2 == null) {
            return 0.0;
        }

        String a = s1.trim();
        String b = s2.trim();

        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }

        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }

        if (a.equalsIgnoreCase(b)) {
            return 1.0;
        }

        int lenA = a.length();
        int lenB = b.length();
        int maxLen = Math.max(lenA, lenB);

        if ((double) Math.abs(lenA - lenB) / maxLen > MAX_LEN_DIFF_RATIO) {
            return 0.0;
        }

        int matchDist = Math.max(maxLen / 2 - 1, 0);
        boolean[] aMatch = new boolean[lenA];
        boolean[] bMatch = new boolean[lenB];

        int matches = 0;

        for (int i = 0; i < lenA; i++) {
            int start = Math.max(0, i - matchDist);
            int end = Math.min(lenB - 1, i + matchDist);
            for (int j = start; j <= end; j++) {
                if (!bMatch[j] && a.charAt(i) == b.charAt(j)) {
                    aMatch[i] = true;
                    bMatch[j] = true;
                    matches++;
                    break;
                }
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        int k = 0;
        int transpositions = 0;

        for (int i = 0; i < lenA; i++) {
            if (aMatch[i]) {
                while (!bMatch[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }

        double jaro = (matches / (double) lenA
                + matches / (double) lenB
                + (matches - transpositions / 2.0) / matches) / 3.0;

        int prefix = 0;

        int maxPrefix = Math.min(4, Math.min(lenA, lenB));

        while (prefix < maxPrefix && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }

        double jw = jaro + prefix * PREFIX_SCALE * (1 - jaro);

        return Math.min(1.0, Math.max(0.0, jw));
    }
}
