package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;
    private static final int BAND_SIZE = 4;
    private static final int BAND_COUNT = SIGNATURE_SIZE / BAND_SIZE;
    private static final double MIN_JACCARD = 0.20;

    @Override
    public Set<IndexPair<Integer, Integer>> generateCandidatePairs(List<String> rows) {
        List<Set<String>> nGramSets = new ArrayList<>();
        List<int[]> signatureList = new ArrayList<>();
        List<Integer> validRowIndices = new ArrayList<>();

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            String row = rows.get(rowIndex);
            Set<String> trigrams = buildTrigramSet(row);

            if (trigrams.size() < 2) {
                continue;
            }

            nGramSets.add(trigrams);
            signatureList.add(buildMinHashSignature(trigrams));
            validRowIndices.add(rowIndex);
        }

        Map<String, List<Integer>> lshBuckets = new HashMap<>();
        for (int localIndex = 0; localIndex < validRowIndices.size(); localIndex++) {
            int[] signature = signatureList.get(localIndex);

            for (int bandIndex = 0; bandIndex < BAND_COUNT; bandIndex++) {
                String bandKey = buildBandKey(signature, bandIndex);
                lshBuckets.computeIfAbsent(bandKey, k -> new ArrayList<>()).add(localIndex);
            }
        }

        Set<IndexPair<Integer, Integer>> candidatePairs = new HashSet<>();
        for (List<Integer> bucketRows : lshBuckets.values()) {
            for (int i = 0; i < bucketRows.size(); i++) {
                for (int j = i + 1; j < bucketRows.size(); j++) {
                    int localIndexA = bucketRows.get(i);
                    int localIndexB = bucketRows.get(j);

                    if (passesJaccardThreshold(nGramSets.get(localIndexA), nGramSets.get(localIndexB))) {
                        int originalRowIndexA = validRowIndices.get(localIndexA);
                        int originalRowIndexB = validRowIndices.get(localIndexB);

                        candidatePairs.add(IndexPair.ofNormalized(originalRowIndexA, originalRowIndexB));
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
            String trigram = rowText.substring(i, i + 3);
            if (!trigram.trim().isEmpty()) {
                trigramSet.add(trigram);
            }
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

    private boolean passesJaccardThreshold(Set<String> nGramSetA, Set<String> nGramSetB) {
        if (nGramSetA.isEmpty() || nGramSetB.isEmpty()) return false;

        Set<String> intersectionSet = new HashSet<>(nGramSetA);
        intersectionSet.retainAll(nGramSetB);

        int unionSize = nGramSetA.size() + nGramSetB.size() - intersectionSet.size();
        double jaccard = (double) intersectionSet.size() / unionSize;

        return jaccard >= MIN_JACCARD;
    }

    public record IndexPair<T extends Comparable<T>, U extends Comparable<U>>(T first, U second) {
        public static IndexPair<Integer, Integer> ofNormalized(int x, int y) {
            return x <= y ? new IndexPair<>(x, y) : new IndexPair<>(y, x);
        }
    }
}




