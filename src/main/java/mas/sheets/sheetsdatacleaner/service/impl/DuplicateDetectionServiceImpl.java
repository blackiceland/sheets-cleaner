package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;

    @Qualifier("heuristicScorers")
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    public DuplicateDetectionServiceImpl(
            MinHashCandidateDetectionService candidateGenerator,
            RowNormalizerService normalizer,
            @Qualifier("heuristicScorers") List<SimilarityScorer> scorers,
            NeuralSimilarityService neuralService
    ) {
        this.candidateGenerator = candidateGenerator;
        this.normalizer = normalizer;
        this.scorers = scorers;
        this.neuralService = neuralService;
    }

    @Override
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {
        List<String> normalizedRows = normalizer.normalizeRows(request.rows());
        Set<IndexPair<Integer, Integer>> rawPairs = candidateGenerator.generateCandidatePairs(normalizedRows);

        Set<IndexPair<Integer, Integer>> candidates = new HashSet<>();

        final double HARD_REJECT_MIN = 0.30;
        final double FAST_REJECT_WEIGHTED = 0.33;
        final double FAST_CONFIRM_WEIGHTED = 0.65;
        final double NEURAL_THRESHOLD = 0.70;

        for (IndexPair<Integer, Integer> pair : rawPairs) {

            String left = normalizedRows.get(pair.first());
            String right = normalizedRows.get(pair.second());

            double tokenScore = 0.0;
            double levScore = 0.0;

            for (SimilarityScorer scorer : scorers) {
                if (scorer instanceof TokenSetRatioScorer tsr) {
                    tokenScore = tsr.calculateScore(left, right);
                } else if (scorer instanceof LevenshteinScorer ls) {
                    levScore = ls.calculateScore(left, right);
                }
            }

            double minScore = Math.min(tokenScore, levScore);
            double weighted = 0.8 * tokenScore + 0.2 * levScore;

            if (minScore <= HARD_REJECT_MIN) {
                log.info("Hard-reject (token={}, lev={}, w={}): {}  |||||  {}", tokenScore, levScore, weighted, left, right);
                continue;
            }

            if (weighted <= FAST_REJECT_WEIGHTED) {
                log.info("Fast-reject (token={}, lev={}, w={}): {}  |||||  {}", tokenScore, levScore, weighted, left, right);
                continue;
            }

            if (weighted >= FAST_CONFIRM_WEIGHTED) {
                log.info("Confirmed (token={}, lev={}, w={}): {}  |||||  {}", tokenScore, levScore, weighted, left, right);
                candidates.add(pair);
                continue;
            }

            double nnScore = neuralService.fetchSimilarityScore(left, right);

            if (nnScore >= NEURAL_THRESHOLD) {
                log.info("Neural approve (nn={}): {}  |||||  {}", nnScore, left, right);
                candidates.add(pair);
            } else {
                log.info("Neural reject  (nn={}): {}  |||||  {}", nnScore, left, right);
            }
        }

        return new DuplicateMatchResponse(null, candidates);
    }
}
