package mas.sheets.sheetsdatacleaner.service.impl;

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
    public Set<IndexPair<Integer, Integer>> generateCandidatePairs(List<String> rows) {
        int n = rows.size();
        MinHashData[] data = new MinHashData[n];

        for (int i = 0; i < n; i++) {
            String row = rows.get(i);
            IntSet grams = collectTrigramHashes(row);
            data[i] = new MinHashData(buildSignature(grams),
                    grams.toArray(),
                    row.replace('|', ' ').length());
        }

        Map<Long, IntList> buckets = new HashMap<>(n * BAND_COUNT / 4);
        for (int idx = 0; idx < n; idx++) {
            int[] sig = data[idx].signature;

            for (int b = 0; b < BAND_COUNT; b++) {
                long key = bandKey(sig, b);
                buckets.computeIfAbsent(key, k -> new IntList()).add(idx);
            }
        }

        Set<IndexPair<Integer, Integer>> pairs = new HashSet<>();
        for (IntList list : buckets.values()) {
            int size = list.size();
            int[] buf = list.elements();

            for (int i = 0; i < size; i++) {
                int a = buf[i];
                for (int j = i + 1; j < size; j++) {
                    int b = buf[j];
                    double thr = dynamicThreshold(data[a].len, data[b].len);

                    boolean passesJ = jaccardPasses(
                            data[a].ngrams, data[b].ngrams, thr, data[a].len, data[b].len
                    );

                    if (passesJ || hasWordOverlap(rows.get(a), rows.get(b))) {
                        pairs.add(IndexPair.ofNormalized(a, b));
                    }
                }
            }
        }
        return pairs;
    }

    private boolean hasWordOverlap(String ra, String rb) {
        Set<String> sa = new HashSet<>();
        for (String w : ra.split("\\W+")) {
            if (w.length() > 3) sa.add(w);
        }
        int common = 0;
        for (String w : rb.split("\\W+")) {
            if (w.length() > 3 && sa.contains(w) && ++common >= 2) {
                return true;
            }
        }
        return false;
    }

    private IntSet collectTrigramHashes(String raw) {
        String s = ' ' + raw.replace('|', ' ') + ' ';
        int len = s.length();
        IntSet set = new IntSet(len);
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= len - 3; i++) {
            int h = MurmurHash3.hash32x86(bytes, i, 3, 0);
            set.add(h);
        }
        return set;
    }

    private int[] buildSignature(IntSet grams) {
        int[] sig = new int[SIGNATURE_SIZE];
        Arrays.fill(sig, Integer.MAX_VALUE);
        for (int g : grams.toArray()) {
            for (int i = 0; i < SIGNATURE_SIZE; i++) {
                int h = mix(g, SEED[i]);
                if (h < sig[i]) sig[i] = h;
            }
        }
        return sig;
    }

    private int mix(int x, int seed) {
        int h = x ^ seed;
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return h;
    }

    private long bandKey(int[] sig, int band) {
        int i = band * BAND_SIZE;
        return ((long) sig[i] << 32) ^ (sig[i + 1] & 0xffffffffL);
    }

    private boolean jaccardPasses(int[] a, int[] b, double thr, int lenA, int lenB) {
        if (a.length == 0 || b.length == 0) return false;

        int inter = 0, i = 0, j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                inter++;
                i++;
                j++;
            } else if (a[i] < b[j]) i++;
            else j++;
        }

        int minOverlap = Math.min(lenA, lenB) < VERY_SHORT_LEN
                ? MIN_OVERLAP_VERY_SHORT
                : MIN_NGRAM_OVERLAP;

        if (inter < minOverlap) return false;

        int union = a.length + b.length - inter;
        return inter >= thr * union;
    }

    private double dynamicThreshold(int lenA, int lenB) {
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

        MinHashData(int[] sig, int[] grams, int len) {
            this.signature = sig;
            Arrays.sort(grams);
            this.ngrams = unique(grams);
            this.len = len;
        }

        private int[] unique(int[] arr) {
            if (arr.length == 0) return arr;
            int w = 1;
            for (int i = 1; i < arr.length; i++)
                if (arr[i] != arr[w - 1]) arr[w++] = arr[i];
            return Arrays.copyOf(arr, w);
        }
    }

    private static final class IntSet {
        private final int[] table;
        private final int mask;
        private int size;

        IntSet(int cap) {
            int m = 1;
            while (m < cap * 2) m <<= 1;
            table = new int[m];
            Arrays.fill(table, Integer.MIN_VALUE);
            mask = m - 1;
        }

        void add(int v) {
            int i = v & mask;
            while (table[i] != Integer.MIN_VALUE && table[i] != v) i = (i + 1) & mask;
            if (table[i] == Integer.MIN_VALUE) {
                table[i] = v;
                size++;
            }
        }

        int[] toArray() {
            int[] out = new int[size];
            int w = 0;
            for (int v : table) if (v != Integer.MIN_VALUE) out[w++] = v;
            return out;
        }
    }

    private static final class IntList {
        private int[] data = new int[4];
        private int size;

        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = v;
        }

        int[] elements() {
            return data;
        }

        int size() {
            return size;
        }
    }

    public record IndexPair<T extends Comparable<T>, U extends Comparable<U>>(T first, U second) {
        public static IndexPair<Integer, Integer> ofNormalized(int x, int y) {
            return x <= y ? new IndexPair<>(x, y) : new IndexPair<>(y, x);
        }
    }
}
