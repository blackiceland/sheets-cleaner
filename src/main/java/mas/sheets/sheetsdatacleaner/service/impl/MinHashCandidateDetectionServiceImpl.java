package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;
    private static final int BAND_SIZE = 4;
    private static final int BANDS_COUNT = SIGNATURE_SIZE / BAND_SIZE;
    private static final double MIN_JACCARD_THRESHOLD = 0.2;

    @Override
    public Set<IndexPair<Integer, Integer>> generateCandidatePairs(List<String> normalizedRows) {
        int n = normalizedRows.size();
        List<Set<String>> ngramsList = new ArrayList<>(n);
        List<int[]> signatures = new ArrayList<>(n);

        for (String row : normalizedRows) {
            String text = row.replace("|", " ");
            Set<String> ngrams = extractNGrams(text);
            ngramsList.add(ngrams);
            signatures.add(computeSignature(ngrams));
        }

        Map<String, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int[] sig = signatures.get(i);
            for (int band = 0; band < BANDS_COUNT; band++) {
                StringBuilder key = new StringBuilder();
                int start = band * BAND_SIZE;
                for (int j = start; j < start + BAND_SIZE; j++) {
                    key.append(sig[j]).append('_');
                }
                buckets.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(i);
            }
        }

        Set<IndexPair<Integer, Integer>> raw = new HashSet<>();
        for (List<Integer> bucket : buckets.values()) {
            int size = bucket.size();
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    raw.add(IndexPair.ofNormalized(bucket.get(i), bucket.get(j)));
                }
            }
        }

        Set<IndexPair<Integer, Integer>> filtered = new HashSet<>();
        for (IndexPair<Integer, Integer> pair : raw) {
            Set<String> a = ngramsList.get(pair.first);
            Set<String> b = ngramsList.get(pair.second);
            if (computeJaccard(a, b) >= MIN_JACCARD_THRESHOLD) {
                filtered.add(pair);
            }
        }

        return filtered;
    }

    private Set<String> extractNGrams(String text) {
        Set<String> ngrams = new HashSet<>();
        String padded = " " + text + " ";
        for (int i = 0; i <= padded.length() - 3; i++) {
            ngrams.add(padded.substring(i, i + 3));
        }
        return ngrams;
    }

    private int[] computeSignature(Set<String> ngrams) {
        int[] sig = new int[SIGNATURE_SIZE];
        Arrays.fill(sig, Integer.MAX_VALUE);
        for (String ng : ngrams) {
            for (int i = 0; i < SIGNATURE_SIZE; i++) {
                int h = Objects.hash(ng, i);
                if (h < sig[i]) {
                    sig[i] = h;
                }
            }
        }
        return sig;
    }

    private double computeJaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> uni = new HashSet<>(a);
        uni.addAll(b);
        return (double) inter.size() / uni.size();
    }

    public record IndexPair<T extends Comparable<T>, U extends Comparable<U>>(T first, U second) {
        public static IndexPair<Integer, Integer> ofNormalized(int x, int y) {
            return x <= y ? new IndexPair<>(x, y) : new IndexPair<>(y, x);
        }
    }
}


