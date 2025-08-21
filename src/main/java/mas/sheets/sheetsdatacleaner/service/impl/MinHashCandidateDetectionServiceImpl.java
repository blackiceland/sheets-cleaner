package mas.sheets.sheetsdatacleaner.service.impl;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.properties.MinHashProps;
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
@RequiredArgsConstructor
public class MinHashCandidateDetectionServiceImpl implements MinHashCandidateDetectionService {

    private final MinHashProps props;

    private static final int SIGNATURE_SIZE = 128;
    private static final int HASH_MASK = (1 << 24) - 1;
    private static final int[] SEED = new int[SIGNATURE_SIZE];

    static {
        Random r = new Random(42);
        for (int i = 0; i < SIGNATURE_SIZE; i++) SEED[i] = r.nextInt();
    }

    private record LshParams(int bandSize, int bandCount, double thrShort, double thrLong) {
    }

    private LshParams defaultParams;
    private final AtomicReference<LshParams> params = new AtomicReference<>();

    @jakarta.annotation.PostConstruct
    void init() {
        defaultParams = new LshParams(
                props.lsh().bandSize(),
                props.lsh().bandCount(),
                props.lsh().thrShort(),
                props.lsh().thrLong()
        );
        params.set(defaultParams);
    }

    @Override
    public Set<IndexPair> generateCandidatePairs(List<RowNorm> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptySet();
        if (rows.size() > props.maxBatch()) throw new IllegalArgumentException("Batch too large");
        if (params.get() == defaultParams) autocalibrate(rows);

        BitSet noisy;
        if (rows.size() < 200) {
            noisy = new BitSet(0);
        } else {
            int limit = Math.max(20, rows.size() / 50);
            Int2IntOpenHashMap df = new Int2IntOpenHashMap(1 << 18);

            rows.forEach(r -> collectNGrams(r == null ? "" : r.value())
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
                if (raw.length() > props.maxLen()) raw = raw.substring(0, props.maxLen());

                int effLen = raw.replaceAll("[^\\p{L}\\p{N}]", "").length();

                String acronym = Arrays.stream(raw.split("\\W+"))
                        .filter(t -> !t.isEmpty())
                        .map(t -> t.substring(0, 1).toLowerCase())
                        .collect(Collectors.joining());

                IntOpenHashSet grams = collectNGrams(raw);

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
                if (pairCnt[a] >= props.maxPairsPerRow()) continue;
                for (int j = i + 1; j < sz; j++) {
                    int b = arr[j];
                    if (pairCnt[b] >= props.maxPairsPerRow()) continue;

                    if (!sameAcronym(buf[a], buf[b]) && !lenCompatible(buf[a], buf[b])) continue;

                    double thr = threshold(buf[a].len, buf[b].len, p);
                    if (passes(buf[a], buf[b], thr) ||
                            wordOverlap(rows.get(a).value(), rows.get(b).value())) {

                        out.add(IndexPair.of(a, b));

                        if (++pairCnt[a] >= props.maxPairsPerRow()) break;
                        pairCnt[b]++;
                    }
                }
            }
        });

        int cap = Math.max(0, props.maxTotalPairs());
        if (cap > 0 && out.size() > cap) {
            List<IndexPair> limited = out.stream().sorted((p1, p2) -> {
                MinHashData x1 = buf[p1.first()];
                MinHashData y1 = buf[p1.second()];
                boolean acr1 = sameAcronym(x1, y1);
                int len1 = Math.min(x1.effLen, y1.effLen);

                MinHashData x2 = buf[p2.first()];
                MinHashData y2 = buf[p2.second()];
                boolean acr2 = sameAcronym(x2, y2);
                int len2 = Math.min(x2.effLen, y2.effLen);

                if (acr1 != acr2) return acr1 ? -1 : 1;
                return Integer.compare(len2, len1);
            }).limit(cap).toList();

            return new HashSet<>(limited);
        }

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

    private static IntOpenHashSet collectNGrams(String raw) {
        String s = " " + raw.replace('|', ' ') + " ";
        int[] cps = s.codePoints().toArray();
        IntOpenHashSet set = new IntOpenHashSet(cps.length);

        for (int i = 0; i <= cps.length - 3; i++)
            set.add(mix3(cps[i], cps[i + 1], cps[i + 2]));

        for (int i = 0; i <= cps.length - 4; i++)
            set.add(mix4(cps[i], cps[i + 1], cps[i + 2], cps[i + 3]));

        return set;
    }

    private static int finalizeHash(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private static int mix3(int a, int b, int c) {
        int h = ((a * 31 + b) * 31) + c;
        return finalizeHash(h);
    }

    private static int mix4(int a, int b, int c, int d) {
        int h = (((a * 31 + b) * 31 + c) * 31) + d;
        return finalizeHash(h);
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

        double contain = (double) inter / Math.min(a.ngrams.length, b.ngrams.length);
        if (contain >= 0.8) return true;

        int base = Math.min(a.len, b.len);
        int dynMin = (base <= 40) ? props.minNgramOverlap()
                : Math.max(props.minNgramOverlap(), (int) Math.ceil(base / 4.0));
        int minOverlap = Math.min(a.len, b.len) < props.veryShortLen()
                ? props.minOverlapVeryShort()
                : dynMin;
        if (inter < minOverlap) return false;

        int union = a.ngrams.length + b.ngrams.length - inter;
        double jacc = (union == 0) ? 1.0 : (double) inter / union;

        return jacc >= thr;
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
        if (m >= props.shortRowMaxLength()) return p.thrLong();
        double k = (double) (m - 5) / (props.shortRowMaxLength() - 5);
        return p.thrShort() - k * (p.thrShort() - p.thrLong());
    }

    private void autocalibrate(List<RowNorm> rows) {
        int total = rows.size();
        if (total < 50) return;

        int sample = Math.min(300, Math.max(50, total / 10));
        Random rnd = new Random(123);
        List<int[]> sigs = new ArrayList<>(sample);
        for (int i = 0; i < sample; i++)
            sigs.add(buildSignature(collectNGrams(rows.get(rnd.nextInt(total)).value()), new BitSet(0)));

        int bestBand = props.lsh().bandSize();
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

        double sThr = props.lsh().thrShort();
        double lThr = props.lsh().thrLong();
        if (bestBand > props.lsh().bandSize()) {
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
