package mas.sheets.sheetsdatacleaner.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minhash")
public record MinHashProps(
        int maxBatch,
        int maxLen,
        int maxPairsPerRow,
        int maxTotalPairs,
        int shortRowMaxLength,
        int veryShortLen,
        int minOverlapVeryShort,
        int minNgramOverlap,
        Lsh lsh
) {
    public record Lsh(int bandSize, int bandCount, double thrShort, double thrLong) {}
}
