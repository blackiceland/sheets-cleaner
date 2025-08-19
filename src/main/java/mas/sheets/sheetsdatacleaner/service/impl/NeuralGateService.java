package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.config.properties.DuplicateDetectorProps;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class NeuralGateService {

    private final DuplicateDetectorProps props;

    public record EvalItem(IndexPair pair, double weightedScore, int maxLen) {}

    public record Split(List<EvalItem> selectedForNeural, List<EvalItem> overflowHighScore) {}

    public Split splitForNeural(List<EvalItem> items) {
        if (items == null || items.isEmpty()) return new Split(List.of(), List.of());

        int max = props.toNeuralMax() == null ? 200 : Math.max(0, props.toNeuralMax());
        int pct = props.toNeuralPct() == null ? 2 : Math.max(0, props.toNeuralPct());
        double overflowMin = props.nnOverflowCandidateMinScore() == null ? 0.50 : props.nnOverflowCandidateMinScore();

        int pctCount = (int) Math.floor(items.size() * (pct / 100.0));
        int limit = Math.min(max, Math.max(pctCount, 50));
        limit = Math.max(0, Math.min(limit, items.size()));

        List<EvalItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(EvalItem::weightedScore).reversed());

        List<EvalItem> selected = sorted.subList(0, limit);
        List<EvalItem> overflow = new ArrayList<>();

        for (int i = limit; i < sorted.size(); i++) {
            EvalItem it = sorted.get(i);
            if (it.weightedScore() >= overflowMin) overflow.add(it);
        }

        log.debug("neural.gate total={} limit={} overflowHighScore={}", items.size(), limit, overflow.size());
        return new Split(selected, overflow);
    }
}


