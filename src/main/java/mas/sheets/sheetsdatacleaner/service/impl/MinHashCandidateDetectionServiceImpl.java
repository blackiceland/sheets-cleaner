package mas.sheets.sheetsdatacleaner.service.impl;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private static final int SIGNATURE_SIZE = 128;
    private static final int SHORT_ROW_MAX_LENGTH = 20;
    private static final int VERY_SHORT_LEN = 18;
    private static final int MIN_OVERLAP_VERY_SHORT = 5;
    private static final int MIN_NGRAM_OVERLAP = 8;
    private static final int MAX_BATCH = 100_000;
    private static final int MAX_LEN = 1_000;
    private static final int MAX_PAIRS_PER_ROW = 30;
    private static final int HASH_MASK = (1 << 24) - 1;
    private static final int[] SEED = new int[SIGNATURE_SIZE];

    static {
        Random r = new Random(42);
        for (int i = 0; i < SIGNATURE_SIZE; i++) SEED[i] = r.nextInt();
    }

    private record LshParams(int bandSize, int bandCount, double thrShort, double thrLong) {
    }

    private static final LshParams DEFAULT_PARAMS = new LshParams(4, SIGNATURE_SIZE / 4, 0.22, 0.23);
    private final AtomicReference<LshParams> params = new AtomicReference<>(DEFAULT_PARAMS);

    @Override
    public Set<IndexPair> generateCandidatePairs(List<RowNorm> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptySet();
        if (rows.size() > MAX_BATCH) throw new IllegalArgumentException("Batch too large");
        if (params.get() == DEFAULT_PARAMS) autocalibrate(rows);

        BitSet noisy;
        if (rows.size() < 200) {
            noisy = new BitSet(0);
        } else {
            int limit = Math.max(20, rows.size() / 50);
            Int2IntOpenHashMap df = new Int2IntOpenHashMap(1 << 18);

            rows.forEach(r -> collectTrigrams(r == null ? "" : r.value())
                    .forEach(g -> df.addTo(g & HASH_MASK, 1)));

            noisy = new BitSet(1 << 24);
            df.int2IntEntrySet().forEach(e -> {
                if (e.getIntValue() >= limit) noisy.set(e.getIntKey());
            });

            df.clear();
        }

        LshParams p = params.get();
        int bandSize = p.bandSize();
        int bandCount = p.bandCount();
        int n = rows.size();

        MinHashData[] buf = new MinHashData[n];

        IntStream.range(0, n).parallel().forEach(i -> {
            try {
                String raw = rows.get(i).value();
                if (raw == null) raw = "";
                raw = Normalizer.normalize(raw, Normalizer.Form.NFKC);
                if (raw.length() > MAX_LEN) raw = raw.substring(0, MAX_LEN);

                int effLen = raw.replaceAll("[^\\p{L}\\p{N}]", "").length();

                String acronym = Arrays.stream(raw.split("\\W+"))
                        .filter(t -> !t.isEmpty())
                        .map(t -> t.substring(0, 1).toLowerCase())
                        .collect(Collectors.joining());

                IntOpenHashSet grams = collectTrigrams(raw);

                buf[i] = new MinHashData(
                        buildSignature(grams, noisy),
                        grams.toIntArray(),
                        raw.replace('|', ' ').length(),
                        effLen,
                        acronym
                );
            } catch (Exception e) {
                log.error("row {} processing failed: {}", i, e.getMessage());
                buf[i] = new MinHashData(new int[SIGNATURE_SIZE], new int[0], 0, 0, "");
            }
        });

        int[] pairCnt = new int[n];
        Map<Long, IntArrayList> buckets = new HashMap<>(n * bandCount / 4);

        for (int idx = 0; idx < n; idx++) {
            int[] sig = buf[idx].signature;
            for (int b = 0; b < bandCount; b++) {
                long key = (((long) sig[b * bandSize]) << 32)
                        ^ (sig[b * bandSize + 1] & 0xffffffffL);
                buckets.computeIfAbsent(key, k -> new IntArrayList()).add(idx);
            }
        }

        Set<IndexPair> out = new HashSet<>();
        buckets.values().forEach(list -> {
            int[] arr = list.elements();
            int sz = list.size();
            for (int i = 0; i < sz; i++) {
                int a = arr[i];
                if (pairCnt[a] >= MAX_PAIRS_PER_ROW) continue;
                for (int j = i + 1; j < sz; j++) {
                    int b = arr[j];
                    if (pairCnt[b] >= MAX_PAIRS_PER_ROW) continue;

                    if (!sameAcronym(buf[a], buf[b]) && !lenCompatible(buf[a], buf[b])) continue;

                    double thr = threshold(buf[a].len, buf[b].len, p);
                    if (passes(buf[a], buf[b], thr) ||
                            wordOverlap(rows.get(a).value(), rows.get(b).value())) {

                        out.add(IndexPair.of(a, b));

                        if (++pairCnt[a] >= MAX_PAIRS_PER_ROW) break;
                        pairCnt[b]++;
                    }
                }
            }
        });

        log.info("{} rows processed", out.size());

        return out;
    }

    private static boolean lenCompatible(MinHashData x, MinHashData y) {
        if (x.effLen < 16 || y.effLen < 16) return true;
        int diff = Math.abs(x.effLen - y.effLen);
        int max = Math.max(x.effLen, y.effLen);

        return diff <= 0.5 * max;
    }

    private static boolean sameAcronym(MinHashData x, MinHashData y) {
        return !x.acronym.isEmpty() && x.acronym.equals(y.acronym);
    }

    private static int[] buildSignature(IntOpenHashSet grams, BitSet noisy) {
        int[] sig = new int[SIGNATURE_SIZE];
        Arrays.fill(sig, Integer.MAX_VALUE);
        IntIterator it = grams.iterator();
        while (it.hasNext()) {
            int g = it.nextInt();
            if (noisy.get(g & HASH_MASK)) continue;
            for (int i = 0; i < SIGNATURE_SIZE; i++) {
                int h = mix(g, SEED[i]);
                if (h < sig[i]) sig[i] = h;
            }
        }

        return sig;
    }

    private static IntOpenHashSet collectTrigrams(String raw) {
        String s = " " + raw.replace('|', ' ') + " ";
        int[] cps = s.codePoints().toArray();
        IntOpenHashSet set = new IntOpenHashSet(cps.length);

        for (int i = 0; i <= cps.length - 3; i++) {
            int h = mix3(cps[i], cps[i + 1], cps[i + 2]);
            set.add(h);
        }

        return set;
    }

    private static int mix3(int a, int b, int c) {
        int h = a;
        h = 31 * h + b;
        h = 31 * h + c;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
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

        int base = Math.min(a.len, b.len);
        int dynMin = (base <= 40) ? MIN_NGRAM_OVERLAP
                : Math.max(MIN_NGRAM_OVERLAP, (int) Math.ceil(base / 4.0));

        int minOverlap = Math.min(a.len, b.len) < VERY_SHORT_LEN
                ? MIN_OVERLAP_VERY_SHORT
                : dynMin;

        if (inter < minOverlap) return false;

        int union = a.ngrams.length + b.ngrams.length - inter;
        return inter >= thr * union;
    }

    private boolean wordOverlap(String a, String b) {
        IntOpenHashSet seen = new IntOpenHashSet();
        Arrays.stream(a.split("\\W+"))
                .filter(t -> t.length() > 3)
                .forEach(t -> seen.add(t.hashCode()));
        int common = 0;
        for (String w : b.split("\\W+"))
            if (w.length() > 3 && seen.contains(w.hashCode()) && ++common >= 2) return true;

        return false;
    }

    private double threshold(int lenA, int lenB, LshParams p) {
        int m = Math.min(lenA, lenB);
        if (m < 5) return p.thrShort();
        if (m >= SHORT_ROW_MAX_LENGTH) return p.thrLong();
        double k = (double) (m - 5) / (SHORT_ROW_MAX_LENGTH - 5);
        return p.thrShort() - k * (p.thrShort() - p.thrLong());
    }

    private void autocalibrate(List<RowNorm> rows) {
        int total = rows.size();
        if (total < 50) return;

        int sample = Math.min(300, Math.max(50, total / 10));
        Random rnd = new Random(123);
        List<int[]> sigs = new ArrayList<>(sample);
        for (int i = 0; i < sample; i++)
            sigs.add(buildSignature(
                    collectTrigrams(rows.get(rnd.nextInt(total)).value()), new BitSet(0)));

        int bestBand = 4;
        double bestΔ = Double.MAX_VALUE;
        for (int cand : new int[]{4, 5, 6, 8}) {
            int bandCnt = SIGNATURE_SIZE / cand;
            long pairs = 0;
            Map<Long, IntArrayList> tmp = new HashMap<>(sample * bandCnt / 4);
            for (int idx = 0; idx < sample; idx++) {
                int[] sig = sigs.get(idx);
                for (int b = 0; b < bandCnt; b++) {
                    long k = (((long) sig[b * cand]) << 32)
                            ^ (sig[b * cand + 1] & 0xffffffffL);
                    tmp.computeIfAbsent(k, l -> new IntArrayList()).add(idx);
                }
            }
            for (IntArrayList l : tmp.values()) {
                int m = l.size();
                pairs += (long) m * (m - 1) / 2;
            }

            double δ = Math.abs(pairs / (double) sample - 0.4);
            if (δ < bestΔ) {
                bestΔ = δ;
                bestBand = cand;
            }
        }

        double sThr = 0.22, lThr = 0.23;
        if (bestBand > 4) {
            sThr += 0.03;
            lThr += 0.03;
        }
        params.set(new LshParams(bestBand, SIGNATURE_SIZE / bestBand, sThr, lThr));
    }

    private static final class MinHashData {
        final int[] signature;
        final int[] ngrams;
        final int len;
        final int effLen;
        final String acronym;

        MinHashData(int[] sig, int[] grams, int len, int effLen, String acr) {
            this.signature = sig;
            this.ngrams = unique(grams);
            this.len = len;
            this.effLen = effLen;
            this.acronym = acr;
        }

        private static int[] unique(int[] arr) {
            if (arr.length == 0) return arr;
            Arrays.sort(arr);
            int w = 1;
            for (int i = 1; i < arr.length; i++)
                if (arr[i] != arr[w - 1]) arr[w++] = arr[i];
            return Arrays.copyOf(arr, w);
        }
    }
}
