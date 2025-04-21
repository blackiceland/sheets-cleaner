package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateGroup;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.enums.MatchConfidenceLevel;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService rowNormalizerService;
    private final List<SimilarityScorer> heuristicScorers;
    private final NeuralSimilarityService neuralSimilarityService;

    public DuplicateDetectionServiceImpl(
            MinHashCandidateDetectionService candidateGenerator,
            RowNormalizerService rowNormalizerService,
            @Qualifier("heuristicScorers") List<SimilarityScorer> heuristicScorers,
            NeuralSimilarityService neuralSimilarityService
    ) {
        this.candidateGenerator = candidateGenerator;
        this.rowNormalizerService = rowNormalizerService;
        this.heuristicScorers = heuristicScorers;
        this.neuralSimilarityService = neuralSimilarityService;
    }

    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.92;
    private static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.70;


    @Override
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {
        List<String> normalizedRows = rowNormalizerService.normalizeRows(request.rows());
        Set<IndexPair<Integer, Integer>> candidatePairs = candidateGenerator.generateCandidatePairs(normalizedRows);

        Map<Integer, Set<Integer>> similarityMap = new HashMap<>();
        Map<IndexPair<Integer, Integer>, MatchConfidenceLevel> pairConfidence = new HashMap<>();

        processCandidatePairs(normalizedRows, candidatePairs, similarityMap, pairConfidence);

        List<Set<Integer>> clusters = findConnectedComponents(similarityMap);

        return buildResponseFromClusters(clusters, pairConfidence);
    }

    private void processCandidatePairs(
            List<String> normalizedRows,
            Set<IndexPair<Integer, Integer>> candidatePairs,
            Map<Integer, Set<Integer>> similarityMap,
            Map<IndexPair<Integer, Integer>, MatchConfidenceLevel> pairConfidence
    ) {
        for (IndexPair<Integer, Integer> rawPair : candidatePairs) {
            IndexPair<Integer, Integer> pair = IndexPair.ofNormalized(rawPair.first(), rawPair.second());
            String left = normalizedRows.get(pair.first());
            String right = normalizedRows.get(pair.second());

            double maxHeuristic = heuristicScorers.stream()
                    .mapToDouble(scorer -> scorer.calculateScore(left, right))
                    .max()
                    .orElse(0.0);

            double neuralScore = neuralSimilarityService.fetchSimilarityScore(left, right);
            double score = Math.max(maxHeuristic, neuralScore);

            if (score >= MEDIUM_CONFIDENCE_THRESHOLD) {
                similarityMap.computeIfAbsent(pair.first(), k -> new HashSet<>()).add(pair.second());
                similarityMap.computeIfAbsent(pair.second(), k -> new HashSet<>()).add(pair.first());

                MatchConfidenceLevel level = score >= HIGH_CONFIDENCE_THRESHOLD
                        ? MatchConfidenceLevel.HIGH
                        : MatchConfidenceLevel.MEDIUM;

                pairConfidence.put(pair, level);
            }
        }
    }

    private DuplicateMatchResponse buildResponseFromClusters(
            List<Set<Integer>> clusters,
            Map<IndexPair<Integer, Integer>, MatchConfidenceLevel> pairConfidence
    ) {
        List<DuplicateGroup> highConfidenceGroups = new ArrayList<>();
        List<DuplicateGroup> mediumConfidenceGroups = new ArrayList<>();

        for (Set<Integer> cluster : clusters) {
            if (cluster.size() <= 1) {
                continue;
            }

            List<Integer> sorted = new ArrayList<>(cluster);
            sorted.sort(Integer::compareTo);
            int base = sorted.getFirst();
            List<Integer> duplicates = sorted.subList(1, sorted.size());

            boolean hasHighConfidence = duplicates.stream()
                    .map(index -> IndexPair.ofNormalized(base, index))
                    .anyMatch(pair -> pairConfidence.getOrDefault(pair, MatchConfidenceLevel.MEDIUM) == MatchConfidenceLevel.HIGH);

            MatchConfidenceLevel overallLevel = hasHighConfidence ? MatchConfidenceLevel.HIGH : MatchConfidenceLevel.MEDIUM;
            DuplicateGroup group = new DuplicateGroup(base, duplicates, overallLevel);

            if (overallLevel == MatchConfidenceLevel.HIGH) {
                highConfidenceGroups.add(group);
            } else {
                mediumConfidenceGroups.add(group);
            }
        }

        return new DuplicateMatchResponse(highConfidenceGroups, mediumConfidenceGroups);
    }

    private List<Set<Integer>> findConnectedComponents(Map<Integer, Set<Integer>> graph) {
        Set<Integer> visited = new HashSet<>();
        List<Set<Integer>> components = new ArrayList<>();

        for (Integer node : graph.keySet()) {
            if (!visited.contains(node)) {
                Set<Integer> cluster = new HashSet<>();
                Queue<Integer> queue = new LinkedList<>();
                queue.add(node);
                visited.add(node);

                while (!queue.isEmpty()) {
                    Integer current = queue.poll();
                    cluster.add(current);

                    for (Integer neighbor : graph.getOrDefault(current, Set.of())) {
                        if (visited.add(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }

                components.add(cluster);
            }
        }

        return components;
    }
}
