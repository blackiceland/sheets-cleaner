package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@SpringBootTest
class DuplicateDetectionServiceTest {

    private final DuplicateDetectionService service = new DuplicateDetectionService();

    @ParameterizedTest(name = "{index} — {0}")
    @MethodSource("provideTestCases")
    void shouldDetectFullDuplicates(String description, List<List<String>> rows, int expectedDuplicatesCount) {
        DuplicateMatchRequest request = new DuplicateMatchRequest(
                "A1:Z999",
                List.of("Col1", "Col2", "Col3"),
                rows,
                "TestSheet",
                "test-spreadsheet-id",
                false
        );

        List<DuplicateMatchResponse> result = service.findDuplicates(request);

        showDetails(description, result);

        assertThat(result)
                .as("Test case: " + description)
                .hasSize(expectedDuplicatesCount);
    }

    private static void showDetails(String description, List<DuplicateMatchResponse> result) {
        System.out.printf("🧪 %s — найдено %d дубликатов: %n", description, result.size());

        for (DuplicateMatchResponse match : result) {
            System.out.printf(
                    "🔁 Дубликат: %s\n   🔹 Индекс оригинальной строки: #%d\n   🔸 Индексы дубликатов %s\n\n",
                    match.normalizedRows(),
                    match.originalRowIndex(),
                    match.duplicateRowIndexes()
            );
        }
    }

    private static Stream<Arguments> provideTestCases() {
        return Stream.of(
                arguments(
                        "No duplicates",
                        List.of(
                                List.of("Alice", "alice@mail.com", "USA"),
                                List.of("Bob", "bob@mail.com", "UK"),
                                List.of("Charlie", "charlie@mail.com", "France")
                        ),
                        0
                ),
                arguments(
                        "One full duplicate",
                        List.of(
                                List.of("Alice", "alice@mail.com", "USA"),
                                List.of("Bob", "bob@mail.com", "UK"),
                                List.of("Alice", "alice@mail.com", "USA")
                        ),
                        1
                ),
                arguments(
                        "Two separate duplicates",
                        List.of(
                                List.of("A", "a@mail.com", "US"),
                                List.of("B", "b@mail.com", "UK"),
                                List.of("A", "a@mail.com", "US"),
                                List.of("B", "b@mail.com", "UK"),
                                List.of("C", "c@mail.com", "CA")
                        ),
                        2
                ),
                arguments(
                        "Duplicates with case and spacing",
                        List.of(
                                List.of(" Alice ", "ALICE@mail.com", " usa "),
                                List.of("alice", "alice@mail.com", "USA"),
                                List.of("ALICE", "Alice@Mail.Com", "USA")
                        ),
                        1
                )
        );
    }
}

