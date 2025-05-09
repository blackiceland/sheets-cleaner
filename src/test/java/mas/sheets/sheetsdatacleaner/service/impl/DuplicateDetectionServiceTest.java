package mas.sheets.sheetsdatacleaner.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.client.EmbeddingApiClient;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.NeuralSimilarityService;
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
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateDetectionServiceTest {

    private static final String CONTAINER_IMAGE = "similarity:0.3.0";
    private static final int CONTAINER_PORT = 5000;

    @Container
    private static final GenericContainer<?> container =
            new GenericContainer<>(DockerImageName.parse(CONTAINER_IMAGE))
                    .withExposedPorts(CONTAINER_PORT)
                    .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                    .withStartupTimeout(Duration.ofMinutes(4));

    @Test
    @DisplayName("detects exact duplicates without neural service")
    void shouldDetectExactDuplicates() {

        List<List<String>> rows = List.of(
                List.of("John Smith", "john.smith@example.com"),     // 0
                List.of("John Smith", "john.smith@example.com"),     // 1 – exact
                List.of("Smith, John", "smith.j@example.com")        // 2
        );

        NeuralSimilarityService neuralMock = Mockito.mock(NeuralSimilarityService.class);
        DuplicateDetectionService service = createService(neuralMock);

        DuplicateMatchResponse resp = service.findDuplicates(new DuplicateMatchRequest(rows));

        assertThat(resp.confirmed()).contains(IndexPair.of(0, 1));
        assertThat(resp.confirmed()).doesNotContain(IndexPair.of(0, 2));

        Mockito.verify(neuralMock, Mockito.never())
                .fetchSimilarityScore(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    @DisplayName("detects similar duplicates via weighted scores")
    void shouldDetectSimilarDuplicates() {

        List<List<String>> rows = List.of(
                List.of("John Smith", "john.smith@example.com"),        // 0
                List.of("Jonathan Smith", "john.smith@example.com"),    // 1 – similar
                List.of("Jane Doe", "jane.doe@example.com")             // 2
        );

        DuplicateDetectionService service =
                createService(Mockito.mock(NeuralSimilarityService.class));

        DuplicateMatchResponse resp = service.findDuplicates(new DuplicateMatchRequest(rows));

        assertThat(resp.candidates()).contains(IndexPair.of(0, 1));
        assertThat(resp.confirmed()).doesNotContain(IndexPair.of(0, 2));
    }

    @ParameterizedTest
    @MethodSource("provideTestRows")
    @DisplayName("detects duplicates using real similarity container")
    void shouldDetectDuplicatesWithContainer(List<List<String>> rows) {

        if (!container.isRunning()) container.start();

        String base = "http://%s:%d/similarity".formatted(
                container.getHost(), container.getMappedPort(CONTAINER_PORT));

        var mapper = new ObjectMapper();
        var http = HttpClient.newHttpClient();
        var apiUri = URI.create(base);
        var exec = Executors.newFixedThreadPool(4);

        EmbeddingApiClient client = new EmbeddingApiClient(mapper, http, apiUri, exec);
        NeuralSimilarityService neural = new NeuralSimilarityServiceImpl(client);
        DuplicateDetectionService service = createService(neural);

        DuplicateMatchResponse resp = service.findDuplicates(new DuplicateMatchRequest(rows));

        /* ожидаемые группы */
        Set<IndexPair> expectedConfirmed = Set.of(
                IndexPair.of(0, 1),
                IndexPair.of(0, 20),
                IndexPair.of(1, 20),
                IndexPair.of(0, 21),
                IndexPair.of(1, 21),
                IndexPair.of(20, 21)
        );

        Set<IndexPair> expectedCandidates = Set.of(
                IndexPair.of(2, 3),
                IndexPair.of(8, 9),
                IndexPair.of(10, 11),
                IndexPair.of(12, 13),
                IndexPair.of(14, 15),
                IndexPair.of(16, 17)
        );

        assertThat(resp.confirmed()).containsAll(expectedConfirmed);
        assertThat(resp.candidates()).containsAll(expectedCandidates);

        exec.shutdownNow();
    }


    private DuplicateDetectionService createService(NeuralSimilarityService neural) {
        var exactDetector = new ExactDuplicateDetectorImpl();
        var minHash = new MinHashCandidateDetectionServiceImpl();
        var normalizer = new RowNormalizerServiceImpl();

        List<SimilarityScorer> scorers = List.of(
                new TokenSetRatioScorer(),
                new LevenshteinScorer()
        );

        return new DuplicateDetectionServiceImpl(
                exactDetector, minHash, normalizer, scorers, neural
        );
    }

    private static Stream<List<List<String>>> provideTestRows() {
        return Stream.of(/* ... */);
    }
}
