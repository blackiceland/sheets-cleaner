package mas.sheets.sheetsdatacleaner.service.impl;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.apache.commons.codec.digest.MurmurHash3;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Slf4j
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;

    private static final int SHORT_ROW_MAX_LENGTH = 20;
    private static final int VERY_SHORT_LEN = 18;
    private static final int MIN_OVERLAP_VERY_SHORT = 5;
    private static final int MIN_NGRAM_OVERLAP = 8;

    private static final int[] SEED = new int[SIGNATURE_SIZE];

    static {
        Random r = new Random(42);
        for (int i = 0; i < SIGNATURE_SIZE; i++) SEED[i] = r.nextInt();
    }

    private static volatile LshParams PARAMS = new LshParams(
            4,
            SIGNATURE_SIZE / 4,
            0.22,
            0.23
    );

    private record LshParams(
            int bandSize,
            int bandCount,
            double thrShort,
            double thrLong
    ) {
    }

    private static volatile boolean tuned = false;

    @Override
    public Set<IndexPair> generateCandidatePairs(List<String> rows) {
        if (!tuned) {
            synchronized (MinHashCandidateDetectionServiceImpl.class) {
                if (!tuned) {
                    autocalibrate(rows);
                    tuned = true;
                }
            }
        }

        LshParams p = PARAMS;
        int bandSize = p.bandSize();
        int bandCount = p.bandCount();

        int n = rows.size();
        MinHashData[] data = new MinHashData[n];
        for (int i = 0; i < n; i++) {
            String row = rows.get(i);
            IntOpenHashSet grams = collectTrigrams(row);
            data[i] = new MinHashData(
                    buildSignature(grams),
                    grams.toIntArray(),
                    row.replace('|', ' ').length()
            );
        }

        Map<Long, IntArrayList> buckets = new HashMap<>(n * bandCount / 4);
        for (int idx = 0; idx < n; idx++) {
            int[] sig = data[idx].signature;
            for (int b = 0; b < bandCount; b++) {
                long key = (((long) sig[b * bandSize]) << 32) ^ (sig[b * bandSize + 1] & 0xffffffffL);
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
                    double thr = threshold(data[a].len, data[b].len, p);
                    if (passes(data[a], data[b], thr) || wordOverlap(rows.get(a), rows.get(b))) {
                        result.add(IndexPair.of(a, b));
                    }
                }
            }
        });
        return result;
    }

    private static void autocalibrate(List<String> rows) {
        int total = rows.size();
        if (total < 50) return;

        int sampleSize = Math.min(300, Math.max(50, total / 10));
        Random rnd = new Random(123);
        List<String> sample = new ArrayList<>(sampleSize);
        for (int i = 0; i < sampleSize; i++) sample.add(rows.get(rnd.nextInt(total)));

        List<int[]> sigs = new ArrayList<>(sampleSize);
        for (String s : sample) sigs.add(buildSignature(collectTrigramsStatic(s)));

        int bestBand = 4;
        double bestDelta = Double.MAX_VALUE;
        for (int cand : new int[]{4, 5, 6, 8}) {
            int bandCnt = SIGNATURE_SIZE / cand;
            long pairs = 0;
            Map<Long, IntArrayList> tmp = new HashMap<>(sampleSize * bandCnt / 4);
            for (int idx = 0; idx < sampleSize; idx++) {
                int[] sig = sigs.get(idx);
                for (int b = 0; b < bandCnt; b++) {
                    long key = (((long) sig[b * cand]) << 32) ^ (sig[b * cand + 1] & 0xffffffffL);
                    tmp.computeIfAbsent(key, k -> new IntArrayList()).add(idx);
                }
            }
            for (IntArrayList list : tmp.values()) {
                int m = list.size();
                pairs += (long) m * (m - 1) / 2;
            }
            double rate = pairs / (double) sampleSize;
            double delta = Math.abs(rate - 0.4);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestBand = cand;
            }
        }

        double shortThr = 0.22;
        double longThr = 0.23;
        if (bestBand > 4) {
            shortThr += 0.03;
            longThr += 0.03;
        }

        PARAMS = new LshParams(
                bestBand,
                SIGNATURE_SIZE / bestBand,
                shortThr,
                longThr
        );
    }

    private static IntOpenHashSet collectTrigramsStatic(String raw) {
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

    private boolean wordOverlap(String a, String b) {
        IntOpenHashSet seen = new IntOpenHashSet();
        Arrays.stream(a.split("\\W+")).filter(s -> s.length() > 3).forEach(s -> seen.add(s.hashCode()));
        int common = 0;
        for (String w : b.split("\\W+")) {
            if (w.length() > 3 && seen.contains(w.hashCode()) && ++common >= 2) return true;
        }
        return false;
    }

    private IntOpenHashSet collectTrigrams(String raw) {
        return collectTrigramsStatic(raw);
    }

    private static int[] buildSignature(IntOpenHashSet grams) {
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

    private static int mix(int x, int seed) {
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
            if (a.ngrams[i] == b.ngrams[j]) {
                inter++;
                i++;
                j++;
            } else if (a.ngrams[i] < b.ngrams[j]) i++;
            else j++;
        }
        int minOverlap = Math.min(a.len, b.len) < VERY_SHORT_LEN ? MIN_OVERLAP_VERY_SHORT : MIN_NGRAM_OVERLAP;
        if (inter < minOverlap) return false;
        int union = a.ngrams.length + b.ngrams.length - inter;

        return inter >= thr * union;
    }

    private double threshold(int lenA, int lenB, LshParams p) {
        int m = Math.min(lenA, lenB);
        if (m < 5) return p.thrShort();
        if (m >= SHORT_ROW_MAX_LENGTH) return p.thrLong();
        double k = (double) (m - 5) / (SHORT_ROW_MAX_LENGTH - 5);

        return p.thrShort() - k * (p.thrShort() - p.thrLong());
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

        private static int[] makeUnique(int[] arr) {
            if (arr.length == 0) return arr;
            Arrays.sort(arr);
            int w = 1;
            for (int i = 1; i < arr.length; i++) if (arr[i] != arr[w - 1]) arr[w++] = arr[i];

            return Arrays.copyOf(arr, w);
        }
    }
}
