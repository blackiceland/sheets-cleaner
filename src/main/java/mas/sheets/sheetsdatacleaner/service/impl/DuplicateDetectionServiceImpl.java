package mas.sheets.sheetsdatacleaner.service.impl;

import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
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

/**
 * Основной сервис поиска дубликатов в таблице.
 * Выполняет последовательность шагов для обнаружения:
 * 1. Нормализация строк
 * 2. Поиск точных дубликатов с помощью ExactDuplicateDetector
 * 3. Генерация кандидатов с помощью MinHash
 * 4. Оценка кандидатов с помощью эвристических скореров
 * 5. Проверка неоднозначных случаев с помощью нейросети
 */
@Slf4j
@Service
public class DuplicateDetectionServiceImpl implements DuplicateDetectionService {

    // Пороги для принятия решений
    private static final double HARD_REJECT_THRESHOLD = 0.30;
    private static final double FAST_REJECT_THRESHOLD = 0.33;
    private static final double FAST_CONFIRM_THRESHOLD = 0.65;
    private static final double NEURAL_CONFIRM_THRESHOLD = 0.70;

    // Веса скореров для взвешенной оценки
    private static final double TOKEN_RATIO_WEIGHT = 0.8;
    private static final double LEVENSHTEIN_WEIGHT = 0.2;

    private final ExactDuplicateDetector exactDetector;
    private final MinHashCandidateDetectionService candidateGenerator;
    private final RowNormalizerService normalizer;
    private final List<SimilarityScorer> scorers;
    private final NeuralSimilarityService neuralService;

    public DuplicateDetectionServiceImpl(
            ExactDuplicateDetector exactDetector,
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
        // Шаг 1: Нормализуем строки
        List<String> normalizedRows = normalizer.normalizeRows(request.rows());

        Set<IndexPair<Integer, Integer>> test = candidateGenerator.generateCandidatePairs(normalizedRows);

        // Шаг 2: Находим точные дубликаты
        var exactResult = exactDetector.detect(normalizedRows);

        var confirmedDuplicates = new HashSet<IndexPair<Integer, Integer>>();
        var candidateDuplicates = new HashSet<IndexPair<Integer, Integer>>();

        // Преобразуем группы в пары для ответа
        for (List<Integer> group : exactResult.duplicateGroups()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    confirmedDuplicates.add(IndexPair.ofNormalized(group.get(i), group.get(j)));
                }
            }
        }

        // Шаг 3: Ищем кандидаты среди оставшихся строк
        var remainingRows = exactResult.remainingRows();
        var originalIndexes = exactResult.originalIndexes();

        Set<IndexPair<Integer, Integer>> candidatePairs = candidateGenerator.generateCandidatePairs(remainingRows);

        log.debug("Generated {} candidate pairs after exact matching", candidatePairs.size());

        // Шаг 4-5: Проверяем каждую пару кандидатов с помощью скореров
        for (IndexPair<Integer, Integer> pair : candidatePairs) {
            // Преобразуем индексы от оставшихся строк к оригинальным
            int originalFirst = originalIndexes.get(pair.first());
            int originalSecond = originalIndexes.get(pair.second());
            var originalPair = IndexPair.ofNormalized(originalFirst, originalSecond);

            // Строки для сравнения
            String leftString = remainingRows.get(pair.first());
            String rightString = remainingRows.get(pair.second());

            // Применяем скореры
            double tokenSetScore = 0;
            double levenshteinScore = 0;

            for (SimilarityScorer scorer : scorers) {
                if (scorer instanceof TokenSetRatioScorer) {
                    tokenSetScore = scorer.calculateScore(leftString, rightString);
                } else if (scorer instanceof LevenshteinScorer) {
                    levenshteinScore = scorer.calculateScore(leftString, rightString);
                }
            }

            // Быстрая фильтрация по минимальному порогу
            double minScore = Math.min(tokenSetScore, levenshteinScore);
            if (minScore <= HARD_REJECT_THRESHOLD) {
                continue;
            }

            // Вычисляем взвешенную оценку
            double weightedScore = TOKEN_RATIO_WEIGHT * tokenSetScore +
                    LEVENSHTEIN_WEIGHT * levenshteinScore;

            // Принятие решения на основе порогов
            if (weightedScore >= FAST_CONFIRM_THRESHOLD) {
                // Пара с высокой оценкой - принимаем сразу
                candidateDuplicates.add(originalPair);
            } else if (weightedScore > FAST_REJECT_THRESHOLD) {
                // Пары в "серой зоне" проверяем нейросетью
                double neuralScore = neuralService.fetchSimilarityScore(leftString, rightString);

                if (neuralScore >= NEURAL_CONFIRM_THRESHOLD) {
                    candidateDuplicates.add(originalPair);
                }
            }
            // Все остальные пары отбрасываем
        }

        return new DuplicateMatchResponse(confirmedDuplicates, candidateDuplicates);
    }
}