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

    private final ExactDuplicateDetectorImpl exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    @Qualifier("heuristicScorers")
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    public DuplicateDetectionServiceImpl(
            ExactDuplicateDetectorImpl exactDetector,
            MinHashCandidateDetectionService candidateGenerator,
            RowNormalizerService normalizer,
            @Qualifier("heuristicScorers") List<SimilarityScorer> scorers,
            NeuralSimilarityService neuralService
    ) {
        this.exactDetector = exactDetector;
        this.candidateGenerator = candidateGenerator;
        this.normalizer = normalizer;
        this.scorers = scorers;
        this.neuralService = neuralService;
    }

    @Override
    public DuplicateMatchResponse findDuplicates(DuplicateMatchRequest request) {
        List<String> normalizedRows = normalizer.normalizeRows(request.rows());
        var exactResult = exactDetector.detect(normalizedRows);
        var confirmedExact = exactResult.exactPairs();
        var remainingRows = exactResult.remainingRows();
        var originalIndexes = exactResult.originalIndexes();

        Set<IndexPair<Integer, Integer>> confirmed = new HashSet<>(confirmedExact);
        Set<IndexPair<Integer, Integer>> candidates = new HashSet<>();

        Set<IndexPair<Integer, Integer>> rawPairs = candidateGenerator.generateCandidatePairs(remainingRows);

        double HARD_REJECT_MIN = 0.30;
        double FAST_REJECT_WEIGHTED = 0.33;
        double FAST_CONFIRM_WEIGHTED = 0.65;
        double NEURAL_THRESHOLD = 0.70;

        for (IndexPair<Integer, Integer> pair : rawPairs) {
            int idxA = originalIndexes.get(pair.first());
            int idxB = originalIndexes.get(pair.second());
            var originalPair = IndexPair.ofNormalized(idxA, idxB);

            if (confirmedExact.contains(originalPair)) {
                continue;
            }

            String left = remainingRows.get(pair.first());
            String right = remainingRows.get(pair.second());

            double tokenScore = 0, levScore = 0;

            for (SimilarityScorer scorer : scorers) {
                if (scorer instanceof TokenSetRatioScorer tsr) {
                    tokenScore = tsr.calculateScore(left, right);
                } else if (scorer instanceof LevenshteinScorer ls) {
                    levScore = ls.calculateScore(left, right);
                }
            }

            double minScore = Math.min(tokenScore, levScore);

            if (minScore <= HARD_REJECT_MIN) {
                continue;
            }

            double weighted = 0.8 * tokenScore + 0.2 * levScore;

            // Любые похожие пары, прошедшие порог, добавляем в кандидаты
            if (weighted >= FAST_CONFIRM_WEIGHTED) {
                candidates.add(originalPair);
            } else if (weighted > FAST_REJECT_WEIGHTED) {
                double nnScore = neuralService.fetchSimilarityScore(left, right);

                if (nnScore >= NEURAL_THRESHOLD) {
                    candidates.add(originalPair);
                }
            }
        }

        return new DuplicateMatchResponse(confirmed, candidates);
    }
}

