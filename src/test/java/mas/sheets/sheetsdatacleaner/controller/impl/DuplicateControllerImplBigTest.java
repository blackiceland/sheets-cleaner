package mas.sheets.sheetsdatacleaner.controller.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.client.EmbeddingApiClient;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.generator.DuplicateDatasetGenerator;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DuplicateControllerImplBigTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    EmbeddingApiClient embedding;

    @Test
    void detectsDuplicatesForThousandRows() throws Exception {
        when(embedding.embedBatch(anyList()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(
                        Collections.nCopies(((List<?>) inv.getArgument(0)).size(), 100.0)));

        List<List<String>> rows = DuplicateDatasetGenerator.build();

        byte[] response = mvc.perform(
                        post("/api/v1/sheets/duplicates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsBytes(rows)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        DuplicateMatchResponse result = mapper.readValue(response, DuplicateMatchResponse.class);

        assertThat(result.confirmed()).hasSize(750);

        int uniqueStart = 750;
        assertThat(result.confirmed())
                .noneMatch(p -> p.first() >= uniqueStart || p.second() >= uniqueStart);

        for (IndexPair pair : result.confirmed()) {
            assertThat(rows.get(pair.first())).isEqualTo(rows.get(pair.second()));
        }
    }
}
