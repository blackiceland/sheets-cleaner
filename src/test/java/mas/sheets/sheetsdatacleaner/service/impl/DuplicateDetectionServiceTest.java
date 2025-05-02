//package mas.sheets.sheetsdatacleaner.service.impl;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
//import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
//import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
//import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
//import org.junit.jupiter.api.TestInstance;
//import org.junit.jupiter.params.ParameterizedTest;
//import org.junit.jupiter.params.provider.MethodSource;
//import org.testcontainers.containers.GenericContainer;
//import org.testcontainers.containers.wait.strategy.Wait;
//import org.testcontainers.utility.DockerImageName;
//
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.time.Duration;
//import java.util.List;
//import java.util.stream.Stream;
//
//import static org.assertj.core.api.Assertions.assertThat;
//
//@TestInstance(TestInstance.Lifecycle.PER_CLASS)
//public class DuplicateDetectionServiceTest {
//
//    @ParameterizedTest
//    @MethodSource("provideTestRows")
//    void shouldDetectDuplicateGroups(List<List<String>> rows) {
//        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("sentence-scorer-crossencoder:latest"))
//                .withExposedPorts(5000)
//                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
//                .withStartupTimeout(Duration.ofSeconds(90))) {
//
//            container.start();
//
//            String baseUrl = "http://" + container.getHost() + ":" + container.getMappedPort(5000) + "/similarity";
//
//            var request = new DuplicateMatchRequest(
//                    "A1:Z999",
//                    List.of("Col1", "Col2", "Col3"),
//                    rows,
//                    "TestSheet",
//                    "test-spreadsheet-id",
//                    false
//            );
//
//            var duplicateService = new DuplicateDetectionServiceImpl(
//                    new MinHashCandidateDetectionServiceImpl(),
//                    new RowNormalizerServiceImpl(),
//                    List.of(
//                            new TokenSetRatioScorer(),
//                            new LevenshteinScorer()
//                    ),
//                    new NeuralSimilarityServiceImpl(new ObjectMapper(), HttpClient.newHttpClient(), URI.create(baseUrl))
//            );
//
//            DuplicateMatchResponse response = duplicateService.findDuplicates(request);
//
//            assertThat(response).isNotNull();
////            assertThat(response.highConfidenceGroups()).isNotEmpty();
//        }
//    }
//
//    private static Stream<List<List<String>>> provideTestRows() {
//        return Stream.of(
//                List.of(
//                        List.of("anton", "markov", ""), // 0
//                        List.of("markov", "anton", ""), // 1 эта пара должна дойти до нейросети
//                        List.of("anton markov", "antonmarkov@gmail.com", ""), // 2 эта пара должна дойти до нейросети
//                        List.of("a. markov", "anton+dev@gmail.com", ""), // 3
//                        List.of("résumé", "façade", "crème brûlée"), // 4
//                        List.of("张伟", "mhmd", "Иван Иванов"), // 5
//                        List.of("", "", ""), // 6
//                        List.of("a b", "a b", "c d"), // 7
//                        List.of("aleksei petrov", "aleksei.petrov@mail.ru", ""), // 8 хэш не считает дублем
//                        List.of("a petrov", "aleksei.petrov+test@mail.ru", ""), // 9 хэш не считает дублем
//                        List.of("ул. Ленина, 15", "lenina street 15", "moscow"), // 10
//                        List.of("улица Ленина, д. 15", "15 lenina", "msk"), // 11
//                        List.of("Москва", "Moscow", ""), // 12
//                        List.of("2024-01-01", "01.01.2024", ""), // 13
//                        List.of("john", "smith", ""), // 14
//                        List.of("j. smith", "", ""), // 15
//                        List.of("ivan ivanov", "", "1985"), // 16 хэш не считает дублем
//                        List.of("ivanov ivan", "", "85"), // 17 хэш не считает дублем
//                        List.of("", "", "no duplicates here"), // 18
//                        List.of("completely", "different", "row"), // 19
//                        List.of("anton markov", "", ""), // 20
//                        List.of("markov anton", "", ""), // 21
//                        List.of("alex petrov", "moskovskaya 12", "01.01.1990"), // 22
//                        List.of("sergey petrov", "tverskaya 8", "02.02.1985"), // 23
//                        List.of("ivan petrov", "ivanov petr", "") // 24
//                )
//        );
//    }
//}
