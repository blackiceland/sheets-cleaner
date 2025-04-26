package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;
    private static final int BAND_SIZE = 4;
    private static final int BAND_COUNT = SIGNATURE_SIZE / BAND_SIZE;
    private static final double SHORT_ROW_THRESHOLD = 0.25;
    private static final double LONG_ROW_THRESHOLD = 0.20;
    private static final int SHORT_ROW_MAX_LENGTH = 20;

    @Override
    public Set<IndexPair<Integer, Integer>> generateCandidatePairs(List<String> rows) {
        int totalSize = rows.size();

        List<Set<String>> nGramSets = new ArrayList<>(totalSize);
        List<int[]> signatureList = new ArrayList<>(totalSize);
        List<Integer> rowLengths = new ArrayList<>(totalSize);

        for (String row : rows) {
            Set<String> trigrams = buildTrigramSet(row);
            nGramSets.add(trigrams);
            signatureList.add(buildMinHashSignature(trigrams));
            rowLengths.add(row.replace("|", " ").length());
        }

        Map<String, List<Integer>> lshBuckets = new HashMap<>();
        for (int rowIndex = 0; rowIndex < totalSize; rowIndex++) {
            int[] signature = signatureList.get(rowIndex);

            for (int bandIndex = 0; bandIndex < BAND_COUNT; bandIndex++) {
                String bandKey = buildBandKey(signature, bandIndex);
                lshBuckets.computeIfAbsent(bandKey, k -> new ArrayList<>()).add(rowIndex);
            }
        }

        Set<IndexPair<Integer, Integer>> candidatePairs = new HashSet<>();
        for (List<Integer> bucketRows : lshBuckets.values()) {
            for (int i = 0; i < bucketRows.size(); i++) {
                for (int j = i + 1; j < bucketRows.size(); j++) {
                    int rowIndexA = bucketRows.get(i);
                    int rowIndexB = bucketRows.get(j);

                    Set<String> nGramSetA = nGramSets.get(rowIndexA);
                    Set<String> nGramSetB = nGramSets.get(rowIndexB);

                    int lengthA = rowLengths.get(rowIndexA);
                    int lengthB = rowLengths.get(rowIndexB);

                    double dynamicThreshold = selectDynamicThreshold(lengthA, lengthB);

                    if (passesJaccardThreshold(nGramSetA, nGramSetB, dynamicThreshold)) {
                        candidatePairs.add(IndexPair.ofNormalized(rowIndexA, rowIndexB));
                    }
                }
            }
        }

        return candidatePairs;
    }

    private Set<String> buildTrigramSet(String row) {
        String rowText = " " + row.replace('|', ' ') + " ";
        Set<String> trigramSet = new HashSet<>();

        for (int i = 0; i <= rowText.length() - 3; i++) {
            trigramSet.add(rowText.substring(i, i + 3));
        }

        return trigramSet;
    }

    private int[] buildMinHashSignature(Set<String> nGramSet) {
        int[] signature = new int[SIGNATURE_SIZE];
        Arrays.fill(signature, Integer.MAX_VALUE);

        for (String nGram : nGramSet) {
            for (int i = 0; i < SIGNATURE_SIZE; i++) {
                signature[i] = Math.min(signature[i], Objects.hash(nGram, i));
            }
        }

        return signature;
    }

    private String buildBandKey(int[] signature, int bandIndex) {
        int start = bandIndex * BAND_SIZE;
        StringBuilder keyBuilder = new StringBuilder();

        for (int i = start; i < start + BAND_SIZE; i++) {
            keyBuilder.append(signature[i]).append('_');
        }

        return keyBuilder.toString();
    }

    private boolean passesJaccardThreshold(Set<String> nGramSetA, Set<String> nGramSetB, double threshold) {
        if (nGramSetA.isEmpty() || nGramSetB.isEmpty()) {
            return false;
        }

        Set<String> intersectionSet = new HashSet<>(nGramSetA);
        intersectionSet.retainAll(nGramSetB);

        int unionSize = nGramSetA.size() + nGramSetB.size() - intersectionSet.size();
        double jaccard = (double) intersectionSet.size() / unionSize;

        return jaccard >= threshold;
    }

    private double selectDynamicThreshold(int lengthA, int lengthB) {
        int minLength = Math.min(lengthA, lengthB);
        return minLength < SHORT_ROW_MAX_LENGTH ? SHORT_ROW_THRESHOLD : LONG_ROW_THRESHOLD;
    }

    public record IndexPair<T extends Comparable<T>, U extends Comparable<U>>(T first, U second) {
        public static IndexPair<Integer, Integer> ofNormalized(int x, int y) {
            return x <= y ? new IndexPair<>(x, y) : new IndexPair<>(y, x);
        }
    }
}
