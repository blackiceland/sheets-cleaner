package mas.sheets.sheetsdatacleaner.service;

public interface NeuralSimilarityService {

    double fetchSimilarityScore(String firstText, String secondText);

}
