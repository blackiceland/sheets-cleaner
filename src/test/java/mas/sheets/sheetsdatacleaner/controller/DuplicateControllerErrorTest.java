package mas.sheets.sheetsdatacleaner.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DuplicateControllerErrorTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    @Test
    void badBase64TokenReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "datasetToken", "not_base64@@",
                "removedRowIds", List.of()
        );

        mockMvc.perform(post("/api/v1/sheets/duplicates/fuzzy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooLargeTokenReturns400() throws Exception {
        String large = "a".repeat(3 * 1024 * 1024);
        Map<String, Object> body = Map.of(
                "datasetToken", large,
                "removedRowIds", List.of()
        );

        mockMvc.perform(post("/api/v1/sheets/duplicates/fuzzy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest());
    }
} 