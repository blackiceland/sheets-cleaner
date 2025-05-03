package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import org.apache.commons.codec.digest.MurmurHash3;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Точный детектор дубликатов.
 * <p>Объединяет строки, которые:</p>
 * <ol>
 *   <li>полностью идентичны (exactIdx);</li>
 *   <li>совпадают после перестановки слов (canonicalIdx);</li>
 *   <li>совпадают по алфавитно‑цифровому Murmur‑хешу (hashIdx) <br>
 *       &nbsp;&nbsp;&nbsp;&nbsp;и <em>либо</em> равны без учёта регистра, <em>либо</em> имеют одинаковый canonical‑ключ.</li>
 * </ol>
 */
@Component
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final int PARALLEL_THRESHOLD = 100_000;

    /* split по ‘|’, иначе — по любым не‑буквенно‑цифровым символам */
    private static final Pattern WORDS = Pattern.compile("[^\\p{IsAlphabetic}\\d]+");
    private static final Pattern PIPE  = Pattern.compile("\\s*\\|\\s*");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");

    /* редкий разделитель для canonicalKey, чтобы избежать неоднозначности */
    private static final String CANON_SEP = "\u0001";

    @Override
    public ExactDetectionResult detect(List<String> normalizedRows) {

        if (normalizedRows == null || normalizedRows.isEmpty()) {
            return new ExactDetectionResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        final int n   = normalizedRows.size();
        boolean  par = n > PARALLEL_THRESHOLD;

        String[] originalKey  = new String[n];
        String[] canonicalKey = new String[n];
        int[]    alnumHash    = new int[n];

        IntStream stream = par ? IntStream.range(0, n).parallel()
                : IntStream.range(0, n);

        stream.forEach(i -> {
            String row = Optional.ofNullable(normalizedRows.get(i)).orElse("").trim();
            originalKey[i] = row;

            /* canonical: токены, отсортированные лексикографически */
            String[] tokens = (row.contains("|") ? PIPE : WORDS).split(row);
            Arrays.sort(tokens);
            canonicalKey[i] = String.join(CANON_SEP, tokens);   // ← избегаем "abc" = "a|bc"

            /* alnum‑hash */
            String alnum = NON_ALNUM.matcher(row).replaceAll("");
            alnumHash[i] = alnum.isEmpty()
                    ? 0
                    : MurmurHash3.hash32x86(alnum.getBytes(StandardCharsets.UTF_8));
        });

        /* индексы */
        Map<String,  List<Integer>> exactIdx  = new HashMap<>();
        Map<String,  List<Integer>> canonIdx  = new HashMap<>();
        Map<Integer, List<Integer>> hashIdx   = new HashMap<>();

        for (int i = 0; i < n; i++) {
            exactIdx .computeIfAbsent(originalKey[i] , k -> new ArrayList<>()).add(i);
            canonIdx .computeIfAbsent(canonicalKey[i], k -> new ArrayList<>()).add(i);
            hashIdx  .computeIfAbsent(alnumHash[i]   , k -> new ArrayList<>()).add(i);
        }

        /* DSU */
        DisjointSet dsu = new DisjointSet(n);
        unite(exactIdx.values(),  dsu);                     // 1
        unite(canonIdx.values(),  dsu);                     // 2
        uniteHash(hashIdx.values(), dsu, originalKey, canonicalKey); // 3

        /* формируем результат */
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            groups.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(i);

        List<List<Integer>> dupGroups   = new ArrayList<>();
        List<String>        remainRows  = new ArrayList<>();
        List<Integer>       remainIndex = new ArrayList<>();

        for (List<Integer> g : groups.values()) {
            if (g.size() > 1) dupGroups.add(g);
            else {
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
            int root = bucket.getFirst();
            for (int j = 1; j < bucket.size(); j++)
                dsu.union(root, bucket.get(j));
        }
    }

    /** Murmur‑корзины объединяем, если строки равны без учёта регистра ИЛИ canonical‑ключ одинаков */
    private void uniteHash(Collection<List<Integer>> buckets,
                           DisjointSet dsu,
                           String[] originalKey,
                           String[] canonicalKey) {
        for (List<Integer> bucket : buckets) {
            if (bucket.size() < 2) continue;
            int first = bucket.getFirst();
            for (int j = 1; j < bucket.size(); j++) {
                int other = bucket.get(j);
                if (originalKey[first].equalsIgnoreCase(originalKey[other])
                        || canonicalKey[first].equals(canonicalKey[other])) {
                    dsu.union(first, other);
                }
            }
        }
    }

    /* ---------- DSU ---------- */

    private static final class DisjointSet {
        private final int[]  parent;
        private final byte[] rank;

        DisjointSet(int n) {
            parent = new int[n];
            rank   = new byte[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {               // итеративное сжатие пути
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int x, int y) {
            int rx = find(x), ry = find(y);
            if (rx == ry) return;

            if (rank[rx] < rank[ry])      parent[rx] = ry;
            else if (rank[rx] > rank[ry]) parent[ry] = rx;
            else { parent[ry] = rx; rank[rx]++; }
        }
    }

    /* ---------- результат ---------- */
    public record ExactDetectionResult(
            List<List<Integer>> duplicateGroups,
            List<String>        remainingRows,
            List<Integer>       originalIndexes
    ) {}
}
