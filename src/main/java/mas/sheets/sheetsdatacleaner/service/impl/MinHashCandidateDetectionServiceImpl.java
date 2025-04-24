package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIG_SIZE = 128;
    private static final int BAND_SIZE = 4;
    private static final int BANDS = SIG_SIZE / BAND_SIZE;
    private static final double THRESHOLD = 0.2;

    @Override
    public Set<IndexPair<Integer, Integer>> generateCandidatePairs(List<String> rows) {
        int n = rows.size();
        List<int[]> sigs = new ArrayList<>(n);
        List<Set<String>> ngs = new ArrayList<>(n);

        for (String row : rows) {
            String t = " " + row.replace("|", " ") + " ";
            Set<String> grams = new HashSet<>();
            for (int i = 0; i <= t.length() - 3; i++) {
                grams.add(t.substring(i, i + 3));
            }
            ngs.add(grams);
            int[] sig = new int[SIG_SIZE];
            Arrays.fill(sig, Integer.MAX_VALUE);
            for (String g : grams) {
                for (int i = 0; i < SIG_SIZE; i++) {
                    sig[i] = Math.min(sig[i], Objects.hash(g, i));
                }
            }
            sigs.add(sig);
        }

        Map<String, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int[] sig = sigs.get(i);
            for (int b = 0; b < BANDS; b++) {
                String key = IntStream.range(b * BAND_SIZE, (b + 1) * BAND_SIZE)
                        .mapToObj(j -> sig[j] + "_")
                        .collect(Collectors.joining());
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }

        Set<IndexPair<Integer, Integer>> out = new HashSet<>();
        for (List<Integer> bucket : buckets.values()) {
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    int a = bucket.get(i), b = bucket.get(j);
                    Set<String> A = ngs.get(a), B = ngs.get(b);
                    Set<String> inter = new HashSet<>(A);
                    inter.retainAll(B);
                    int uni = A.size() + B.size() - inter.size();
                    if (uni > 0 && (double) inter.size() / uni >= THRESHOLD) {
                        out.add(IndexPair.ofNormalized(a, b));
                    }
                }
            }
        }
        return out;
    }

    public record IndexPair<T extends Comparable<T>, U extends Comparable<U>>(T first, U second) {
        public static IndexPair<Integer, Integer> ofNormalized(int x, int y) {
            return x <= y ? new IndexPair<>(x, y) : new IndexPair<>(y, x);
        }
    }
}


