package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
class DetectionPipelineConsistencyTest {

    @Autowired
    private DuplicateDetectionService detectionService;

    @Autowired
    private ExactDuplicateService exactService;

    @Test
    void fullPipelineEqualsExactPlusFuzzy() {
        List<List<String>> rows = List.of(
                List.of("john", "smith"),
                List.of("smith", "john"),
                List.of("completely", "different")
        );

        DuplicateMatchRequest req = new DuplicateMatchRequest(rows, false);

        DuplicateMatchResponse full = detectionService.findDuplicates(req);

        var stage = exactService.detectExact(req);
        DuplicateMatchResponse composed = detectionService.detectFuzzy(stage.normalizedRows(), stage.exactResult());

        Assertions.assertEquals(full.confirmed(), composed.confirmed());
        Assertions.assertEquals(full.candidates(), composed.candidates());
    }

    @Test
    void headerShiftMaintained() {
        List<List<String>> rows = List.of(
                List.of("header1", "header2"),
                List.of("john", "smith"),
                List.of("smith", "john")
        );

        DuplicateMatchRequest reqWithHeader = new DuplicateMatchRequest(rows, true);
        DuplicateMatchRequest reqNoHeader = new DuplicateMatchRequest(rows.subList(1, rows.size()), false);

        DuplicateMatchResponse fullHeader = detectionService.findDuplicates(reqWithHeader);
        DuplicateMatchResponse fullNoHeader = detectionService.findDuplicates(reqNoHeader);

        fullNoHeader.confirmed().forEach(p -> {
            mas.sheets.sheetsdatacleaner.model.IndexPair shifted = mas.sheets.sheetsdatacleaner.model.IndexPair.of(p.first() + 1, p.second() + 1);
            Assertions.assertTrue(fullHeader.confirmed().contains(shifted));
        });
    }
} 