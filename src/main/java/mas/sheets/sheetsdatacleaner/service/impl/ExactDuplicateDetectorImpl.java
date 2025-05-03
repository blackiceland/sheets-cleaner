package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import org.apache.commons.codec.digest.MurmurHash3;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Component
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final int PARALLEL_THRESHOLD = 100_000;

    private static final Pattern WORDS = Pattern.compile("[^\\p{IsAlphabetic}\\d]+");
    private static final Pattern PIPE = Pattern.compile("\\s*\\|\\s*");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");

    @Override
    public ExactDetectionResult detect(List<String> normalizedRows) {

        if (normalizedRows == null || normalizedRows.isEmpty()) {
            return new ExactDetectionResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        int n = normalizedRows.size();
        boolean par = n > PARALLEL_THRESHOLD;

        String[] originalKey = new String[n];
        String[] canonicalKey = new String[n];
        int[] alnumHash = new int[n];

        IntStream range = par ? IntStream.range(0, n).parallel()
                : IntStream.range(0, n);

        range.forEach(i -> {
            String row = Optional.ofNullable(normalizedRows.get(i))
                    .orElse("")
                    .trim();

            originalKey[i] = row;

            /* канонический порядок слов */
            String[] tokens = (row.contains("|") ? PIPE : WORDS).split(row);
            Arrays.sort(tokens);
            canonicalKey[i] = String.join("", tokens);

            /* hash по alnum‑символам */
            String alnum = NON_ALNUM.matcher(row).replaceAll("");
            alnumHash[i] = alnum.isEmpty()
                    ? 0
                    : MurmurHash3.hash32x86(alnum.getBytes(StandardCharsets.UTF_8));
        });

        /* ---------- индексы ---------- */
        Map<String, List<Integer>> exactIdx = new HashMap<>();
        Map<String, List<Integer>> canonIdx = new HashMap<>();
        Map<Integer, List<Integer>> hashIdx = new HashMap<>();

        for (int i = 0; i < n; i++) {
            exactIdx.computeIfAbsent(originalKey[i], k -> new ArrayList<>()).add(i);
            canonIdx.computeIfAbsent(canonicalKey[i], k -> new ArrayList<>()).add(i);
            hashIdx.computeIfAbsent(alnumHash[i], k -> new ArrayList<>()).add(i);
        }

        /* ---------- DSU объединение ---------- */
        DisjointSet dsu = new DisjointSet(n);
        unite(exactIdx.values(), dsu);                // полное совпадение
        unite(canonIdx.values(), dsu);                // перестановка слов
        uniteHash(hashIdx.values(), dsu, originalKey); // alnum‑hash + equals

        /* ---------- формирование итоговых групп ---------- */
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = dsu.find(i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(i);
        }

        List<List<Integer>> dupGroups = new ArrayList<>();
        List<String> remainRows = new ArrayList<>();
        List<Integer> remainIndex = new ArrayList<>();

        for (List<Integer> g : groups.values()) {
            if (g.size() > 1) {
                dupGroups.add(g);
            } else {
                int idx = g.getFirst();
                remainRows.add(normalizedRows.get(idx));
                remainIndex.add(idx);
            }
        }

        return new ExactDetectionResult(dupGroups, remainRows, remainIndex);
    }

    /* ---------- helpers ---------- */

    private void unite(Collection<List<Integer>> buckets, DisjointSet dsu) {
        for (List<Integer> bucket : buckets) {
            if (bucket.size() < 2) continue;
            int first = bucket.getFirst();
            for (int j = 1; j < bucket.size(); j++) dsu.union(first, bucket.get(j));
        }
    }

    /**
     * Murmur‑корзины объединяем только после equals‑проверки
     */
    private void uniteHash(Collection<List<Integer>> buckets,
                           DisjointSet dsu,
                           String[] originalKey) {
        for (List<Integer> bucket : buckets) {
            if (bucket.size() < 2) continue;
            int first = bucket.getFirst();
            for (int j = 1; j < bucket.size(); j++) {
                int other = bucket.get(j);
                if (originalKey[first].equals(originalKey[other])) {
                    dsu.union(first, other);
                }
            }
        }
    }

    /* ---------- DSU ---------- */
    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;

        DisjointSet(int n) {
            parent = new int[n];
            rank = new byte[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            if (parent[x] != x) parent[x] = find(parent[x]);
            return parent[x];
        }

        void union(int x, int y) {
            int rx = find(x), ry = find(y);
            if (rx == ry) return;
            if (rank[rx] < rank[ry]) parent[rx] = ry;
            else if (rank[rx] > rank[ry]) parent[ry] = rx;
            else {
                parent[ry] = rx;
                rank[rx]++;
            }
        }
    }

    /* ---------- результат ---------- */
    public record ExactDetectionResult(
            List<List<Integer>> duplicateGroups,
            List<String> remainingRows,
            List<Integer> originalIndexes
    ) {
    }
}
