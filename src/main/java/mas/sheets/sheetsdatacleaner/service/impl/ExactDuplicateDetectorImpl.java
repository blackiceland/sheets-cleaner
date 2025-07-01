package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.enums.ClusterKind;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Component
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final int PARALLEL_THRESHOLD = 100_000;
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");

    @Override
    public ExactDetectionResult detect(List<RowNorm> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ExactDetectionResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap());
        }

        int n = rows.size();
        boolean par = n > PARALLEL_THRESHOLD;

        String[] key = new String[n];

        IntStream range = par ? IntStream.range(0, n).parallel() : IntStream.range(0, n);
        range.forEach(i -> {
            String row = Optional.ofNullable(rows.get(i).value()).orElse("").trim();
            key[i] = NON_ALNUM.matcher(row).replaceAll("").toLowerCase(Locale.ROOT);
        });

        Map<String, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < n; i++) {
            buckets.computeIfAbsent(key[i], k -> new ArrayList<>()).add(i);
        }

        DisjointSet dsu = new DisjointSet(n);
        unite(buckets.values(), dsu);

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(i);
        }

        List<List<Integer>> dupGroups = new ArrayList<>();
        List<RowNorm> remainRows = new ArrayList<>();
        List<Integer> remainIdxSrc = new ArrayList<>();
        Map<Integer, RowMeta> metaByIdx = new HashMap<>();

        for (List<Integer> g : groups.values()) {
            if (g.size() > 1) {
                int canon = g.getFirst();
                UUID clusterId = UUID.randomUUID();
                metaByIdx.put(rows.get(canon).idx(), new RowMeta(rows.get(canon).idx(), clusterId, ClusterKind.CANON));
                for (int j = 1; j < g.size(); j++) {
                    int pos = g.get(j);
                    metaByIdx.put(rows.get(pos).idx(), new RowMeta(rows.get(pos).idx(), clusterId, ClusterKind.EXACT));
                }
                dupGroups.add(g.stream().map(i -> rows.get(i).idx()).toList());
            } else {
                int pos = g.getFirst();
                remainRows.add(rows.get(pos));
                remainIdxSrc.add(rows.get(pos).idx());
            }
        }

        return new ExactDetectionResult(dupGroups, remainRows, remainIdxSrc, metaByIdx);
    }

    private void unite(Collection<List<Integer>> buckets, DisjointSet dsu) {
        for (List<Integer> b : buckets) {
            if (b.size() < 2) continue;
            int root = b.getFirst();
            for (int j = 1; j < b.size(); j++) dsu.union(root, b.get(j));
        }
    }

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
}
