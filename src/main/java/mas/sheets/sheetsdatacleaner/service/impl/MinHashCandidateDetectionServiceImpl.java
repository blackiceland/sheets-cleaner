package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;
    private static final int BAND_SIZE = 4;
    private static final int NGRAM_SIZE = 3;

    public Set<IndexPair<Integer, Integer>> generateCandidatePairs(List<String> normalizedRows) {
        Map<String, List<Integer>> bandKeyToRowIndexes = new HashMap<>();

        for (int rowIndex = 0; rowIndex < normalizedRows.size(); rowIndex++) {
            String rowText = normalizedRows.get(rowIndex);

            if (rowText == null || rowText.isBlank()) {
                continue;
            }

            Set<String> characterNGrams = extractNGrams(rowText);

            if (characterNGrams.isEmpty()) {
                continue;
            }

            int[] minHashVector = computeMinHashSignature(characterNGrams);
            int totalBands = SIGNATURE_SIZE / BAND_SIZE;

            for (int bandIndex = 0; bandIndex < totalBands; bandIndex++) {
                String bandKey = buildBandKey(minHashVector, bandIndex);
                bandKeyToRowIndexes.computeIfAbsent(bandKey, k -> new ArrayList<>()).add(rowIndex);
            }
        }

        Set<IndexPair<Integer, Integer>> candidateRowPairs = new HashSet<>();

        for (List<Integer> rowIndexes : bandKeyToRowIndexes.values()) {
            for (int i = 0; i < rowIndexes.size(); i++) {
                for (int j = i + 1; j < rowIndexes.size(); j++) {
                    int a = rowIndexes.get(i);
                    int b = rowIndexes.get(j);
                    candidateRowPairs.add(new IndexPair<>(Math.min(a, b), Math.max(a, b)));
                }
            }
        }

        return candidateRowPairs;
    }

    private Set<String> extractNGrams(String input) {
        Set<String> ngrams = new HashSet<>();
        String padded = " " + input + " ";

        for (int i = 0; i < padded.length() - NGRAM_SIZE + 1; i++) {
            ngrams.add(padded.substring(i, i + NGRAM_SIZE));
        }

        return ngrams;
    }

    private int[] computeMinHashSignature(Set<String> ngrams) {
        int[] signature = new int[SIGNATURE_SIZE];
        Arrays.fill(signature, Integer.MAX_VALUE);

        for (String ngram : ngrams) {
            for (int i = 0; i < SIGNATURE_SIZE; i++) {
                int hash = Objects.hash(ngram, i);
                signature[i] = Math.min(signature[i], hash);
            }
        }

        return signature;
    }

    private String buildBandKey(int[] signature, int band) {
        int start = band * BAND_SIZE;
        int end = start + BAND_SIZE;
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = start; i < end; i++) {
            stringBuilder.append(signature[i]).append("_");
        }

        return stringBuilder.toString();
    }

    public record IndexPair<T extends Comparable<T>, U extends Comparable<U>>(T first, U second) {

        public static IndexPair<Integer, Integer> ofNormalized(int a, int b) {
            return a <= b ? new IndexPair<>(a, b) : new IndexPair<>(b, a);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IndexPair<?, ?> that = (IndexPair<?, ?>) o;
            return Objects.equals(first, that.first) && Objects.equals(second, that.second);
        }

        @Override
        public int hashCode() {
            return Objects.hash(first, second);
        }
    }

}

