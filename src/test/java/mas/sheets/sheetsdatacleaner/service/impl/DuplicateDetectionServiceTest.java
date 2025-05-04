package mas.sheets.sheetsdatacleaner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.service.MinHashCandidateDetectionService;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import mas.sheets.sheetsdatacleaner.service.impl.MinHashCandidateDetectionServiceImpl.IndexPair;
import mas.sheets.sheetsdatacleaner.similarity.scorer.SimilarityScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.LevenshteinScorer;
import mas.sheets.sheetsdatacleaner.similarity.scorer.impl.TokenSetRatioScorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DuplicateDetectionServiceTest {

    private static final String CONTAINER_IMAGE = "sentence-scorer-crossencoder:latest";
    private static final int CONTAINER_PORT = 5000;
    private static final String HEALTH_ENDPOINT = "/health";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);

    @Container
    private static final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(CONTAINER_IMAGE))
            .withExposedPorts(CONTAINER_PORT)
            .waitingFor(Wait.forHttp(HEALTH_ENDPOINT).forStatusCode(200))
            .withStartupTimeout(STARTUP_TIMEOUT);

    @Test
    @DisplayName("Should detect exact duplicates without neural service")
    void shouldDetectExactDuplicates() {
        // Arrange
        List<List<String>> rows = List.of(
                List.of("John Smith", "john.smith@example.com"),
                List.of("John Smith", "john.smith@example.com"), // Exact duplicate
                List.of("Smith, John", "smith.j@example.com")    // Not exact but similar
        );

        NeuralSimilarityService mockNeuralService = Mockito.mock(NeuralSimilarityService.class);

        var service = createDuplicateService(mockNeuralService);
        var request = new DuplicateMatchRequest(rows);

        // Act
        DuplicateMatchResponse response = service.findDuplicates(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.confirmed()).isNotEmpty();
        assertThat(response.confirmed()).contains(IndexPair.ofNormalized(0, 1));
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(0, 2));

        // Verify that neural service wasn't used for exact duplicates
        Mockito.verify(mockNeuralService, Mockito.never())
                .fetchSimilarityScore(Mockito.eq("John Smith | john.smith@example.com"),
                        Mockito.eq("John Smith | john.smith@example.com"));
    }

    @Test
    @DisplayName("Should correctly handle similar duplicates using weighted scores")
    void shouldDetectSimilarDuplicatesWithWeightedScores() {
        // Arrange
        List<List<String>> rows = List.of(
                List.of("John Smith", "john.smith@example.com"),
                List.of("Jonathan Smith", "john.smith@example.com"), // Similar but high weighted score
                List.of("Jane Doe", "jane.doe@example.com")         // Different
        );

        NeuralSimilarityService mockNeuralService = Mockito.mock(NeuralSimilarityService.class);

        var service = createDuplicateService(mockNeuralService);
        var request = new DuplicateMatchRequest(rows);

        // Act
        DuplicateMatchResponse response = service.findDuplicates(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.confirmed()).contains(IndexPair.ofNormalized(0, 1));
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(0, 2));
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(1, 2));
    }

    @ParameterizedTest
    @MethodSource("provideTestRows")
    @DisplayName("Should detect duplicates with real container")
    void shouldDetectDuplicatesWithRealContainer(List<List<String>> rows) {
        container.start();

        String baseUrl = "http://" + container.getHost() + ":" + container.getMappedPort(CONTAINER_PORT) + "/similarity";

        var request = new DuplicateMatchRequest(rows);

        var neuralService = new NeuralSimilarityServiceImpl(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create(baseUrl)
        );

        var service = createDuplicateService(neuralService);

        // Act
        DuplicateMatchResponse response = service.findDuplicates(request);

        // Assert
        assertThat(response).isNotNull();

        // Expected duplicate pairs (high confidence)
        Set<IndexPair<Integer, Integer>> expectedPairs = Set.of(
                IndexPair.ofNormalized(0, 1),    // anton/markov vs markov/anton
                IndexPair.ofNormalized(0, 20),   // anton/markov vs anton markov
                IndexPair.ofNormalized(1, 20),   // markov/anton vs anton markov
                IndexPair.ofNormalized(1, 21),   // markov/anton vs markov anton
                IndexPair.ofNormalized(2, 3),    // anton markov/gmail vs a. markov/gmail
                IndexPair.ofNormalized(8, 9),    // aleksei petrov vs a petrov with similar emails
                IndexPair.ofNormalized(10, 11),  // ул. Ленина vs улица Ленина
                IndexPair.ofNormalized(12, 13),  // Москва vs Moscow
                IndexPair.ofNormalized(14, 15),  // john smith vs j. smith
                IndexPair.ofNormalized(16, 17),  // ivan ivanov vs ivanov ivan
                IndexPair.ofNormalized(20, 21)   // anton markov vs markov anton
        );

        // Verify core duplicate pairs are found
        for (IndexPair<Integer, Integer> pair : expectedPairs) {
            assertThat(response.confirmed()).contains(pair);
        }

        // Verify non-duplicates are not flagged
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(0, 4));   // anton markov vs résumé
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(0, 14));  // anton markov vs john smith
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(4, 5));   // résumé vs 张伟
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(18, 19)); // no duplicates vs completely different
        assertThat(response.confirmed()).doesNotContain(IndexPair.ofNormalized(22, 24)); // alex petrov vs ivan petrov
    }

    private static Stream<List<List<String>>> provideTestRows() {
        return Stream.of(
                List.of(
                        List.of("anton", "markov", ""), // 0
                        List.of("markov", "anton", ""), // 1
                        List.of("anton markov", "antonmarkov@gmail.com", ""), // 2
                        List.of("a. markov", "anton+dev@gmail.com", ""), // 3
                        List.of("résumé", "façade", "crème brûlée"), // 4
                        List.of("张伟", "mhmd", "Иван Иванов"), // 5
                        List.of("", "", ""), // 6
                        List.of("a b", "a b", "c d"), // 7
                        List.of("aleksei petrov", "aleksei.petrov@mail.ru", ""), // 8
                        List.of("a petrov", "aleksei.petrov+test@mail.ru", ""), // 9
                        List.of("ул. Ленина, 15", "lenina street 15", "moscow"), // 10
                        List.of("улица Ленина, д. 15", "15 lenina", "msk"), // 11
                        List.of("Москва", "Moscow", ""), // 12
                        List.of("москва", "moscow", "russia"), // 13
                        List.of("john", "smith", ""), // 14
                        List.of("j. smith", "", ""), // 15
                        List.of("ivan ivanov", "", "1985"), // 16
                        List.of("ivanov ivan", "", "85"), // 17
                        List.of("", "", "no duplicates here"), // 18
                        List.of("completely", "different", "row"), // 19
                        List.of("anton markov", "", ""), // 20
                        List.of("markov anton", "", ""), // 21
                        List.of("alex petrov", "moskovskaya 12", "01.01.1990"), // 22
                        List.of("sergey petrov", "tverskaya 8", "02.02.1985"), // 23
                        List.of("ivan petrov", "ivanov petr", ""), // 24

                        List.of("Michael Brown", "mbrown@example.com", ""), // 25
                        List.of("Mike Brown", "michael.brown@example.com", ""), // 26
                        List.of("123-456-7890", "", ""), // 27
                        List.of("+1 (123) 456-7890", "", ""), // 28
                        List.of("Robert Smith", "", "1977-05-15"), // 29
                        List.of("Bob Smith", "", "15.05.1977"), // 30
                        List.of("Johnson & Johnson Co.", "", ""), // 31
                        List.of("Johnson and Johnson Company", "", ""), // 32
                        List.of("San Francisco, CA", "", "USA"), // 33
                        List.of("SF, California", "", "United States"), // 34
                        List.of("software engineer", "5 years experience", ""), // 35
                        List.of("senior software developer", "5+ yrs exp", ""), // 36
                        List.of("maria.garcia@gmail.com", "", ""), // 37
                        List.of("m.garcia+work@gmail.com", "", ""), // 38
                        List.of("202-555-0123", "Washington DC", ""), // 39
                        List.of("(202) 555-0123", "Washington, D.C.", ""), // 40
                        List.of("St. Petersburg", "Russia", ""), // 41
                        List.of("Saint-Petersburg", "RU", ""), // 42
                        List.of("Dr. William Jones", "MD", "Cardiology"), // 43
                        List.of("William Jones, M.D.", "Cardiologist", ""), // 44
                        List.of("100 Main St", "Apt 3B", "New York, NY"), // 45
                        List.of("100 Main Street", "Apartment 3B", "NYC"), // 46
                        List.of("Project Manager", "IT Department", "2010-2015"), // 47
                        List.of("PM", "Information Technology", "2010-15"), // 48
                        List.of("Apple Inc.", "", "Technology"), // 49
                        List.of("Apple Incorporated", "", "Tech") // 50
                )
        );
    }

    private DuplicateDetectionServiceImpl createDuplicateService(NeuralSimilarityService neuralService) {
        ExactDuplicateDetectorImpl exactDetector = new ExactDuplicateDetectorImpl();
        MinHashCandidateDetectionService candidateGenerator = new MinHashCandidateDetectionServiceImpl();
        RowNormalizerService normalizer = new RowNormalizerServiceImpl();

        List<SimilarityScorer> scorers = List.of(
                new TokenSetRatioScorer(),
                new LevenshteinScorer()
        );

        return new DuplicateDetectionServiceImpl(
                exactDetector,
                candidateGenerator,
                normalizer,
                scorers,
                neuralService
        );
    }
}