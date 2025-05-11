package mas.sheets.sheetsdatacleaner.service.impl;

import it.unimi.dsi.fastutil.ints.*;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.apache.commons.codec.digest.MurmurHash3;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;
    private static final int BAND_SIZE = 4;
    private static final int BAND_COUNT = SIGNATURE_SIZE / BAND_SIZE;

    private static final int SHORT_ROW_MAX_LENGTH = 20;
    private static final int VERY_SHORT_LEN = 18;

    private static final double SHORT_ROW_THRESHOLD = 0.22;
    private static final double LONG_ROW_THRESHOLD = 0.23;

    private static final int MIN_OVERLAP_VERY_SHORT = 5;
    private static final int MIN_NGRAM_OVERLAP = 8;

    private static final int[] SEED = new int[SIGNATURE_SIZE];
    static {
        Random r = new Random(42);
        for (int i = 0; i < SIGNATURE_SIZE; i++) SEED[i] = r.nextInt();
    }

    @Override
    public Set<IndexPair> generateCandidatePairs(List<String> rows) {
        int n = rows.size();
        MinHashData[] data = new MinHashData[n];

        for (int i = 0; i < n; i++) {
            String row = rows.get(i);
            IntOpenHashSet grams = collectTrigrams(row);
            data[i] = new MinHashData(buildSignature(grams),
                    grams.toIntArray(),
                    row.replace('|', ' ').length());
        }

        Map<Long, IntArrayList> buckets = new HashMap<>(n * BAND_COUNT / 4);
        for (int idx = 0; idx < n; idx++) {
            int[] sig = data[idx].signature;
            for (int b = 0; b < BAND_COUNT; b++) {
                long key = (((long) sig[b * BAND_SIZE]) << 32) ^ (sig[b * BAND_SIZE + 1] & 0xffffffffL);
                buckets.computeIfAbsent(key, k -> new IntArrayList()).add(idx);
            }
        }

        Set<IndexPair> result = new HashSet<>();
        buckets.values().forEach(list -> {
            int[] arr = list.elements();
            int sz = list.size();
            for (int i = 0; i < sz; i++) {
                int a = arr[i];
                for (int j = i + 1; j < sz; j++) {
                    int b = arr[j];
                    double thr = threshold(data[a].len, data[b].len);
                    if (passes(data[a], data[b], thr) ||
                            wordOverlap(rows.get(a), rows.get(b))) {
                        result.add(IndexPair.of(a, b));
                    }
                }
            }
        });
        return result;
    }

    private boolean wordOverlap(String a, String b) {
        IntOpenHashSet seen = new IntOpenHashSet();
        Arrays.stream(a.split("\\W+")).filter(s -> s.length() > 3)
                .forEach(s -> seen.add(s.hashCode()));
        int common = 0;
        for (String w : b.split("\\W+")) {
            if (w.length() > 3 && seen.contains(w.hashCode()) && ++common >= 2) return true;
        }
        return false;
    }

    private IntOpenHashSet collectTrigrams(String raw) {
        String s = ' ' + raw.replace('|', ' ') + ' ';
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length;
        IntOpenHashSet set = new IntOpenHashSet(len);
        for (int i = 0; i <= len - 3; i++) {
            int h = MurmurHash3.hash32x86(bytes, i, 3, 0);
            set.add(h);
        }
        return set;
    }

    private int[] buildSignature(IntOpenHashSet grams) {
        int[] sig = new int[SIGNATURE_SIZE];
        Arrays.fill(sig, Integer.MAX_VALUE);
        IntIterator it = grams.iterator();
        while (it.hasNext()) {
            int g = it.nextInt();
            for (int i = 0; i < SIGNATURE_SIZE; i++) {
                int h = mix(g, SEED[i]);
                if (h < sig[i]) sig[i] = h;
            }
        }
        return sig;
    }

    private int mix(int x, int seed) {
        int h = x ^ seed;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private boolean passes(MinHashData a, MinHashData b, double thr) {
        if (a.ngrams.length == 0 || b.ngrams.length == 0) return false;

        int inter = 0, i = 0, j = 0;
        while (i < a.ngrams.length && j < b.ngrams.length) {
            if (a.ngrams[i] == b.ngrams[j]) { inter++; i++; j++; }
            else if (a.ngrams[i] < b.ngrams[j]) i++; else j++;
        }

        int minOverlap = Math.min(a.len, b.len) < VERY_SHORT_LEN
                ? MIN_OVERLAP_VERY_SHORT : MIN_NGRAM_OVERLAP;
        if (inter < minOverlap) return false;

        int union = a.ngrams.length + b.ngrams.length - inter;
        return inter >= thr * union;
    }

    private double threshold(int lenA, int lenB) {
        int m = Math.min(lenA, lenB);
        if (m < 5) return SHORT_ROW_THRESHOLD;
        if (m >= SHORT_ROW_MAX_LENGTH) return LONG_ROW_THRESHOLD;
        double k = (double) (m - 5) / (SHORT_ROW_MAX_LENGTH - 5);
        return SHORT_ROW_THRESHOLD - k * (SHORT_ROW_THRESHOLD - LONG_ROW_THRESHOLD);
    }

    private static final class MinHashData {
        final int[] signature;
        final int[] ngrams;
        final int len;

        MinHashData(int[] signature, int[] grams, int len) {
            this.signature = signature;
            this.ngrams = makeUnique(grams);
            this.len = len;
        }

        private int[] makeUnique(int[] arr) {
            if (arr.length == 0) return arr;
            Arrays.sort(arr);
            int w = 1;
            for (int i = 1; i < arr.length; i++)
                if (arr[i] != arr[w - 1]) arr[w++] = arr[i];
            return Arrays.copyOf(arr, w);
        }
    }
}
