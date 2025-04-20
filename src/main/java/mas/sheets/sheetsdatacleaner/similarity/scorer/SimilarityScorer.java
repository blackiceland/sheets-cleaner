package mas.sheets.sheetsdatacleaner.similarity.scorer;

public interface SimilarityScorer {

    double calculateScore(String first, String second);

}
