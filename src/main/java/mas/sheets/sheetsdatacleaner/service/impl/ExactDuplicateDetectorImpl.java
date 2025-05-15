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

    /* ───────────────- константы и паттерны -────────────────────────── */

    private static final int PARALLEL_THRESHOLD = 100_000;

    private static final Pattern WORDS = Pattern.compile("[^\\p{IsAlphabetic}\\d]+");
    private static final Pattern PIPE = Pattern.compile("\\s*\\|\\s*");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");

    /**
     * ISBN-10 / ISBN-13 (допускаем «ISBN:», пробелы и дефисы)
     */
    private static final Pattern ISBN = Pattern.compile(
            "(?i)\\b(?:isbn(?::|\\s))?\\s*(97[89][- ]?)?\\d{1,5}[- ]?\\d{1,7}[- ]?\\d{1,7}[- ]?[\\dX]\\b");

    /**
     * MD5 / SHA-1 / SHA-256 (32 / 40 / 64 hex)
     */
    private static final Pattern HEX_HASH =
            Pattern.compile("\\b[0-9a-fA-F]{32}\\b|\\b[0-9a-fA-F]{40}\\b|\\b[0-9a-fA-F]{64}\\b");

    /**
     * очень упрощённый URL-детектор (до нормализации уже «пример.com/…»)
     */
    private static final Pattern URL =
            Pattern.compile("^[a-z][a-z0-9+.-]*://.*|\\w+\\.[a-z]{2,}.*", Pattern.CASE_INSENSITIVE);

    /**
     * телефон: ≥10 цифр (плюс знаки «+», «(», «)», «-», пробел)
     */
    private static final Pattern PHONE_DIGITS = Pattern.compile("\\D");

    private static final int EMPTY_HASH = 0x9E3779B9;
    private static final String CANON_SEP = "\u0001";

    /* ───────────────- тип строки -──────────────────────────────────── */

    private enum DataType {PHONE, ISBN, HASH, URL, PLAIN}

    /* ───────────────- public-метод -────────────────────────────────── */

    @Override
    public ExactDetectionResult detect(List<String> normalizedRows) {

        if (normalizedRows == null || normalizedRows.isEmpty()) {
            return new ExactDetectionResult(
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        int n = normalizedRows.size();
        boolean par = n > PARALLEL_THRESHOLD;

        String[] originalKey = new String[n];
        String[] canonicalKey = new String[n];
        int[] alnumHash = new int[n];
        DataType[] types = new DataType[n];

        IntStream rng = par ? IntStream.range(0, n).parallel() : IntStream.range(0, n);

        rng.forEach(i -> {
            String row = Optional.ofNullable(normalizedRows.get(i)).orElse("").trim();
            originalKey[i] = row;

            // canonical
            String[] toks = (row.contains("|") ? PIPE : WORDS).split(row);
            Arrays.sort(toks);
            canonicalKey[i] = String.join(CANON_SEP, toks);

            // хэш по алфануму
            String alnum = NON_ALNUM.matcher(row).replaceAll("");
            alnumHash[i] = alnum.isEmpty()
                    ? EMPTY_HASH
                    : MurmurHash3.hash32x86(alnum.getBytes(StandardCharsets.UTF_8));

            // тип строки
            types[i] = detectType(row);
        });

        /* ── индексация ─────────────────────────────────────────────── */

        Map<String, List<Integer>> exactIdx = new HashMap<>();
        Map<String, List<Integer>> canonIdx = new HashMap<>();
        Map<Integer, List<Integer>> hashIdx = new HashMap<>();

        for (int i = 0; i < n; i++) {
            exactIdx.computeIfAbsent(originalKey[i], k -> new ArrayList<>()).add(i);
            canonIdx.computeIfAbsent(canonicalKey[i], k -> new ArrayList<>()).add(i);
            if (alnumHash[i] != EMPTY_HASH)   // <-- пустые / 1-символ. не кладём
                hashIdx.computeIfAbsent(alnumHash[i], k -> new ArrayList<>()).add(i);
        }

        DisjointSet dsu = new DisjointSet(n);
        unite(exactIdx.values(), dsu);
        unite(canonIdx.values(), dsu);
        uniteHash(hashIdx.values(), dsu, originalKey, canonicalKey, types);

        /* ── финальные группы ───────────────────────────────────────── */

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++)
            groups
                    .computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(i);

        List<List<Integer>> dupGroups = new ArrayList<>();
        List<String> remainRows = new ArrayList<>();
        List<Integer> remainIndexes = new ArrayList<>();

        for (List<Integer> g : groups.values()) {
            if (g.size() > 1) dupGroups.add(g);
            else {
                int idx = g.getFirst();
                remainRows.add(normalizedRows.get(idx));
                remainIndexes.add(idx);
            }
        }
        return new ExactDetectionResult(dupGroups, remainRows, remainIndexes);
    }

    /* ───────────────- объединение корзин -──────────────────────────── */

    private void unite(Collection<List<Integer>> buckets, DisjointSet dsu) {
        for (List<Integer> b : buckets) {
            if (b.size() < 2) continue;
            int root = b.getFirst();
            for (int j = 1; j < b.size(); j++) dsu.union(root, b.get(j));
        }
    }

    /**
     * unite по Murmur-хэшу с доп. проверками и с учётом типа данных
     */
    private void uniteHash(Collection<List<Integer>> buckets,
                           DisjointSet dsu,
                           String[] originalKey,
                           String[] canonicalKey,
                           DataType[] types) {

        for (List<Integer> bucket : buckets) {
            if (bucket.size() < 2) continue;

            for (int i = 0; i < bucket.size(); i++) {
                int a = bucket.get(i);

                for (int j = i + 1; j < bucket.size(); j++) {
                    int b = bucket.get(j);

                    /* 1. строки разного «жёсткого» типа — пропускаем */
                    if (types[a] != types[b]) continue;

                    /* 2. Jaccard ≥ 0.5 по токенам (быстрое отсечение) */
                    if (!jaccardAtLeastHalf(canonicalKey[a], canonicalKey[b])) continue;

                    /* 3. длина строк отличается не более чем на 2 символа */
                    if (Math.abs(originalKey[a].length() - originalKey[b].length()) > 2) continue;

                    /* 4. Алфанум-Levenshtein ≤ 1 */
                    String s1 = NON_ALNUM.matcher(originalKey[a]).replaceAll("");
                    String s2 = NON_ALNUM.matcher(originalKey[b]).replaceAll("");
                    if (levenshteinGt1(s1, s2)) continue;

                    dsu.union(a, b);
                }
            }
        }
    }

    private static boolean jaccardAtLeastHalf(String canonA, String canonB) {
        // canonX  =  token1␁token2␁…  (␁ = CANON_SEP)
        String[] a = canonA.split(CANON_SEP);
        String[] b = canonB.split(CANON_SEP);
        int i = 0, j = 0, inter = 0;
        while (i < a.length && j < b.length) {
            int cmp = a[i].compareTo(b[j]);
            if (cmp == 0) {
                inter++;
                i++;
                j++;
            } else if (cmp < 0) i++;
            else j++;
        }
        int union = a.length + b.length - inter;
        return union == 0 || (double) inter / union >= 0.5;
    }
    /* ───────────────- детектируем DataType -───────────────────────── */

    private static DataType detectType(String row) {
        if (row.isEmpty()) return DataType.PLAIN;
        if (ISBN.matcher(row).find()) return DataType.ISBN;
        if (HEX_HASH.matcher(row).find()) return DataType.HASH;
        if (URL.matcher(row).matches()) return DataType.URL;

        String digits = PHONE_DIGITS.matcher(row).replaceAll("");
        if (digits.length() >= 10) return DataType.PHONE;

        return DataType.PLAIN;
    }

    /* ───────────────- Levenshtein ≤ 1 -────────────────────────────── */

    private static boolean levenshteinGt1(String s1, String s2) {
        int len1 = s1.length(), len2 = s2.length();
        if (Math.abs(len1 - len2) > 1) return true;

        int edits = 0, i = 0, j = 0;
        while (i < len1 && j < len2) {
            if (s1.charAt(i) == s2.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++edits > 1) return true;
            if (len1 > len2) i++;
            else if (len2 > len1) j++;
            else {
                i++;
                j++;
            }
        }
        return edits + (len1 - i) + (len2 - j) > 1;
    }

    /* ───────────────- DSU -────────────────────────────────────────── */

    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;

        DisjointSet(int n) {
            parent = new int[n];
            rank = new byte[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
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

    /* ───────────────- DTO -────────────────────────────────────────── */

    public record ExactDetectionResult(
            List<List<Integer>> duplicateGroups,
            List<String> remainingRows,
            List<Integer> originalIndexes) {
    }
}
