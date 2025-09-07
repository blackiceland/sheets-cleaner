package mas.sheets.sheetsdatacleaner.controller.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.ExactStageResult;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateDetector;
import mas.sheets.sheetsdatacleaner.service.ExactDuplicateService;
import mas.sheets.sheetsdatacleaner.config.GoogleTokenValidator;
import mas.sheets.sheetsdatacleaner.util.ApiPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Testcontainers
class DuplicateControllerImplTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", () -> redis.getHost());
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("context.ttl-seconds", () -> 300);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DuplicateDetectionService duplicateDetectionService;

    @MockitoBean
    private ExactDuplicateService exactDuplicateService;

    @MockitoBean
    private ExactDuplicateDetector exactDuplicateDetector;

    @MockitoBean
    private GoogleTokenValidator googleTokenValidator;

    @Test
    void detectExactDuplicates_returnsUuidAndRowsMapping() throws Exception {
        List<RowNorm> normalized = List.of(
                RowNorm.of(0, "alpha"),
                RowNorm.of(1, "beta")
        );

        DuplicateMatchResponse exactResponse = new DuplicateMatchResponse(Set.of(), Set.of(), List.of());
        ExactDetectionResult exactDetectionResult = new ExactDetectionResult(List.of(), List.of(), List.of(), Map.of());
        ExactStageResult stage = new ExactStageResult(exactResponse, normalized, exactDetectionResult);

        when(exactDuplicateService.detectExact(any())).thenReturn(stage);

        String resp = mockMvc.perform(post(ApiPaths.DUPLICATES_EXACT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[[\"A\"],[\"B\"]],\"hasHeaders\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].idx").value(0))
                .andExpect(jsonPath("$.rows[1].idx").value(1))
                .andExpect(jsonPath("$.duplicateResponse").exists())
                .andReturn().getResponse().getContentAsString();

        String token = new ObjectMapper().readTree(resp).get("datasetToken").asText();
        assertThat(token).isNotBlank();
    }

    @Test
    void exactThenFuzzy_tokenIsSingleUse() throws Exception {
        List<RowNorm> normalized = List.of(
                RowNorm.of(0, "A"),
                RowNorm.of(1, "B")
        );
        DuplicateMatchResponse exactResponse = new DuplicateMatchResponse(Set.of(), Set.of(), List.of());
        ExactDetectionResult exactDet = new ExactDetectionResult(List.of(), List.of(), List.of(), Map.of());
        ExactStageResult stage = new ExactStageResult(exactResponse, normalized, exactDet);
        when(exactDuplicateService.detectExact(any())).thenReturn(stage);
        when(exactDuplicateDetector.detect(any())).thenReturn(exactDet);
        when(duplicateDetectionService.detectFuzzy(any(), any())).thenReturn(exactResponse);

        String reqBody = "{\"rows\":[[\"A\"],[\"B\"]],\"hasHeaders\":false}";
        String resp = mockMvc.perform(post(ApiPaths.DUPLICATES_EXACT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reqBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = new ObjectMapper().readTree(resp).get("datasetToken").asText();
        String fuzzy = "{\"datasetToken\":\"" + token + "\",\"removedRowIds\":[]}";

        mockMvc.perform(post(ApiPaths.DUPLICATES_FUZZY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fuzzy))
                .andExpect(status().isOk());

        mockMvc.perform(post(ApiPaths.DUPLICATES_FUZZY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fuzzy))
                .andExpect(status().isGone());
    }
}



