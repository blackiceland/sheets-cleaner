package mas.sheets.sheetsdatacleaner.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "duplicate-detector")
public record DuplicateDetectorProps(
        double hardRejectShort,
        double hardRejectLong,
        double fastRejectThreshold,
        double fastConfirmShort,
        double fastConfirmLong,
        int shortLenLimit,
        int nnLenLimit,
        double nnConfirmShort,
        double nnConfirmLong,
        double tokenWeight,
        double levWeight,
        double jwWeight,
        int neuralBatchSize,
        int maxInFlight,
        int workerMultiplier,
        Integer toNeuralMax,
        Integer toNeuralPct,
        Double nnOverflowCandidateMinScore
) {
}
