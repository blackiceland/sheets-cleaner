package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExactDuplicateDetectorImpl implements ExactDuplicateDetector {

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{Alnum}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final int MEDIUM_BUCKET_THRESHOLD = 100;
    private static final int LARGE_BUCKET_THRESHOLD = 1000;

    private static final int MAX_RANDOM_COMPARISONS = 5;

    private static final long RANDOM_SEED = 42L;

    public ExactDetectionResult detect(List<String> normalizedRows) {
        if (normalizedRows == null) {
            log.warn("Received null list of normalized rows");
            return new ExactDetectionResult(Collections.emptySet(), Collections.emptyList(), Collections.emptyList());
        }

        if (normalizedRows.isEmpty()) {
            log.info("Received empty list of normalized rows");
            return new ExactDetectionResult(Collections.emptySet(), Collections.emptyList(), Collections.emptyList());
        }

        log.info("Starting exact duplicate detection for {} rows", normalizedRows.size());
        long startTime = System.nanoTime();

        try {
            Map<String, List<Integer>> k0 = createIndex(normalizedRows, this::identityNormalizer, "Original text");
            Map<String, List<Integer>> k1 = createIndex(normalizedRows, this::sortTokens, "Sorted tokens");
            Map<String, List<Integer>> k2 = createIndex(normalizedRows, this::squeezeAlnum, "Alphanumeric only");
            Map<String, List<Integer>> k3 = createDigitsIndex(normalizedRows);

            Set<IndexPair<Integer, Integer>> exactPairs = new HashSet<>();

            long pairsStartTime = System.nanoTime();
            collectPairsFromIndex(k0, exactPairs, "Original text");
            collectPairsFromIndex(k1, exactPairs, "Sorted tokens");
            collectPairsFromIndex(k2, exactPairs, "Alphanumeric only");
            collectPairsFromIndex(k3, exactPairs, "Digits only");
            long pairsElapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pairsStartTime);

            log.info("Pairs collection completed in {}ms. Total {} duplicate pairs found",
                    pairsElapsedTime, exactPairs.size());

            ExactDetectionResult result = createResult(normalizedRows, exactPairs);

            long totalElapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            log.info("Exact duplicate detection completed in {}ms. Found {} unique pairs, {} rows remaining",
                    totalElapsedTime, exactPairs.size(), result.remainingRows().size());

            return result;

        } catch (Exception e) {
            log.error("Unexpected error during exact duplicate detection", e);

            return createFallbackResult(normalizedRows);
        }
    }

    private Map<String, List<Integer>> createIndex(
            List<String> rows,
            StringNormalizer normalizer,
            String indexName) {

        Map<String, List<Integer>> index = new HashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            if (row == null) {
                log.debug("Skipping null row at index {}", i);
                continue;
            }

            try {
                String normalizedKey = normalizer.normalize(row);
                if (normalizedKey != null && !normalizedKey.isEmpty()) {
                    put(index, normalizedKey, i);
                }
            } catch (Exception e) {
                log.warn("Error normalizing row at index {}: {}", i, e.getMessage());
            }
        }

        logIndexingStats(index, indexName, rows.size());

        return index;
    }

    private Map<String, List<Integer>> createDigitsIndex(List<String> rows) {
        Map<String, List<Integer>> index = new HashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            String row = rows.get(i);
            if (row == null) continue;

            try {
                String digitsKey = digitsOnly(row);
                if (digitsKey.length() >= 4) {
                    put(index, digitsKey, i);
                }
            } catch (Exception e) {
                log.warn("Error extracting digits from row at index {}: {}", i, e.getMessage());
            }
        }

        logIndexingStats(index, "Digits only", rows.size());
        return index;
    }

    @FunctionalInterface
    private interface StringNormalizer {
        String normalize(String input);
    }

    private String identityNormalizer(String input) {
        return input;
    }

    private void logIndexingStats(Map<String, List<Integer>> index, String indexName, int totalRows) {
        int uniqueKeys = index.size();
        int maxBucketSize = index.values().stream().mapToInt(List::size).max().orElse(0);

        Map<String, Long> bucketSizeDistribution = index.values().stream()
                .collect(Collectors.groupingBy(
                        list -> {
                            if (list.size() < 2) return "Size 1";
                            if (list.size() < MEDIUM_BUCKET_THRESHOLD) return "Size 2-99";
                            if (list.size() < LARGE_BUCKET_THRESHOLD) return "Size 100-999";
                            return "Size 1000+";
                        },
                        Collectors.counting()
                ));

        log.info("{} index: {} unique keys ({}% of total rows), max bucket size: {}",
                indexName, uniqueKeys, Math.round((double)uniqueKeys / totalRows * 100), maxBucketSize);

        log.debug("{} bucket distribution: {}", indexName, bucketSizeDistribution);
    }

    private static void put(Map<String, List<Integer>> map, String key, int idx) {
        if (key == null || key.isEmpty()) {
            return;
        }
        map.computeIfAbsent(key, __ -> new ArrayList<>(2)).add(idx);
    }

    private void collectPairsFromIndex(Map<String, List<Integer>> map, Set<IndexPair<Integer, Integer>> out, String indexName) {
        if (map == null || map.isEmpty()) {
            log.debug("Empty map for index: {}", indexName);
            return;
        }

        int smallBuckets = 0;
        int mediumBuckets = 0;
        int largeBuckets = 0;
        int totalPairsAdded = 0;

        long startTime = System.nanoTime();

        try {
            for (Map.Entry<String, List<Integer>> entry : map.entrySet()) {
                List<Integer> bucket = entry.getValue();
                if (bucket.size() < 2) continue;

                int bucketSize = bucket.size();
                int pairsBeforeProcess = out.size();

                try {
                    if (bucketSize < MEDIUM_BUCKET_THRESHOLD) {
                        processSmallBucket(bucket, out);
                        smallBuckets++;
                    } else if (bucketSize < LARGE_BUCKET_THRESHOLD) {
                        processMediumBucket(bucket, out);
                        mediumBuckets++;
                    } else {
                        processLargeBucket(bucket, out, indexName, entry.getKey());
                        largeBuckets++;
                    }

                    int pairsAdded = out.size() - pairsBeforeProcess;
                    totalPairsAdded += pairsAdded;

                    if (pairsAdded > 50) {
                        log.debug("Large number of pairs ({}) found for key '{}' in {} index",
                                pairsAdded, truncateKey(entry.getKey(), 30), indexName);
                    }

                } catch (Exception e) {
                    log.warn("Error processing bucket with key '{}' in {}: {}",
                            truncateKey(entry.getKey(), 30), indexName, e.getMessage());
                }
            }

            long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            log.info("{} index: processed {} small, {} medium, {} large buckets in {}ms, added {} pairs",
                    indexName, smallBuckets, mediumBuckets, largeBuckets, elapsedTime, totalPairsAdded);

        } catch (Exception e) {
            log.error("Error in collectPairs for {}", indexName, e);
        }
    }

    private void processSmallBucket(List<Integer> bucket, Set<IndexPair<Integer, Integer>> out) {
        int size = bucket.size();
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                out.add(IndexPair.ofNormalized(bucket.get(i), bucket.get(j)));
            }
        }
    }

    private void processMediumBucket(List<Integer> bucket, Set<IndexPair<Integer, Integer>> out) {
        int size = bucket.size();
        int numAnchors = (int) Math.sqrt(size);
        int step = Math.max(1, size / numAnchors);

        for (int anchor = 0; anchor < size; anchor += step) {
            int anchorIndex = bucket.get(anchor);

            for (int i = 0; i < size; i++) {
                if (i != anchor) {
                    out.add(IndexPair.ofNormalized(anchorIndex, bucket.get(i)));
                }
            }
        }
    }

    private void processLargeBucket(List<Integer> bucket, Set<IndexPair<Integer, Integer>> out,
                                    String indexName, String key) {
        int size = bucket.size();
        log.warn("Very large bucket detected in {} index: size={}, key='{}'",
                indexName, size, truncateKey(key, 50));

        int sampleSize = Math.min(MEDIUM_BUCKET_THRESHOLD * 3,
                Math.max(MEDIUM_BUCKET_THRESHOLD,
                        (int)Math.sqrt(size * 10)));

        log.debug("Using sample size of {} for bucket of size {}", sampleSize, size);

        List<Integer> sampleIndices = selectDeterministicSample(bucket, sampleSize);

        for (int i = 0; i < sampleIndices.size(); i++) {
            int idx1 = bucket.get(sampleIndices.get(i));

            for (int j = i + 1; j < sampleIndices.size(); j++) {
                int idx2 = bucket.get(sampleIndices.get(j));
                out.add(IndexPair.ofNormalized(idx1, idx2));
            }

            addExtraComparisons(bucket, sampleIndices, idx1, out, i);
        }
    }

    private List<Integer> selectDeterministicSample(List<Integer> bucket, int sampleSize) {
        int size = bucket.size();
        Set<Integer> indices = new HashSet<>(sampleSize);

        int step = Math.max(1, size / sampleSize);
        for (int i = 0; i < size && indices.size() < sampleSize * 0.7; i += step) {
            indices.add(i);
        }

        Random random = new Random(RANDOM_SEED);
        while (indices.size() < sampleSize) {
            indices.add((random.nextInt(1000000) % size + size) % size);
        }

        return new ArrayList<>(indices);
    }

    private void addExtraComparisons(List<Integer> bucket, List<Integer> sampleIndices,
                                     int idx1, Set<IndexPair<Integer, Integer>> out, int baseIndex) {
        int size = bucket.size();
        Set<Integer> sampleSet = new HashSet<>(sampleIndices);

        for (int k = 0; k < MAX_RANDOM_COMPARISONS; k++) {
            int offset = (baseIndex * 17 + k * 31) % size;
            if (!sampleSet.contains(offset)) {
                out.add(IndexPair.ofNormalized(idx1, bucket.get(offset)));
            }
        }
    }

    private static String truncateKey(String key, int maxLength) {
        if (key == null) return "null";
        return key.length() <= maxLength ? key : key.substring(0, maxLength) + "...";
    }

    private ExactDetectionResult createResult(List<String> normalizedRows,
                                              Set<IndexPair<Integer, Integer>> exactPairs) {
        Set<Integer> rowsToSkip = exactPairs.stream()
                .flatMap(pair -> Arrays.stream(new Integer[]{pair.first(), pair.second()}))
                .collect(Collectors.toSet());

        List<String> remainingRows = new ArrayList<>(normalizedRows.size() - rowsToSkip.size());
        List<Integer> indexMap = new ArrayList<>(normalizedRows.size() - rowsToSkip.size());

        for (int i = 0; i < normalizedRows.size(); i++) {
            if (!rowsToSkip.contains(i)) {
                indexMap.add(i);
                remainingRows.add(normalizedRows.get(i));
            }
        }

        return new ExactDetectionResult(exactPairs, remainingRows, indexMap);
    }

    private ExactDetectionResult createFallbackResult(List<String> normalizedRows) {
        List<Integer> indexMap = new ArrayList<>(normalizedRows.size());
        for (int i = 0; i < normalizedRows.size(); i++) {
            indexMap.add(i);
        }
        return new ExactDetectionResult(Collections.emptySet(), normalizedRows, indexMap);
    }

    private String sortTokens(String row) {
        if (row == null) return "";

        try {
            String[] tokens = WHITESPACE.split(row.trim());

            return Arrays.stream(tokens)
                    .filter(t -> t != null && !t.equals("|"))
                    .sorted()
                    .collect(Collectors.joining(" "));
        } catch (Exception e) {
            log.warn("Error in sortTokens: {}", e.getMessage());
            return row;
        }
    }

    private String squeezeAlnum(String row) {
        if (row == null) return "";

        try {
            String cleaned = NON_ALNUM.matcher(row).replaceAll("").toLowerCase(Locale.ROOT);
            return Normalizer.normalize(cleaned, Normalizer.Form.NFKC);
        } catch (Exception e) {
            log.warn("Error in squeezeAlnum: {}", e.getMessage());
            return row;
        }
    }

    private String digitsOnly(String row) {
        if (row == null) return "";

        try {
            return row.replaceAll("\\D", "");
        } catch (Exception e) {
            log.warn("Error in digitsOnly: {}", e.getMessage());
            return "";
        }
    }

    public record ExactDetectionResult(
            Set<IndexPair<Integer, Integer>> exactPairs,
            List<String> remainingRows,
            List<Integer> originalIndexes
    ) {}
}