package mas.sheets.sheetsdatacleaner.controller.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.generator.DuplicateDatasetGenerator;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateControllerImplTest {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("similarity:0.3.0");

    @Container
    static final GenericContainer<?> similarity =
            new GenericContainer<>(IMAGE)
                    .withExposedPorts(5000)
                    .waitingFor(
                            Wait.forHttp("/health")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(3)))
                    .withReuse(true);

    @DynamicPropertySource
    static void registerBaseUrl(DynamicPropertyRegistry registry) {
        String url = "http://" + similarity.getHost() + ':' + similarity.getMappedPort(5000) + "/similarity";
        registry.add("similarity.base-url", () -> url);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void detectsDuplicatesForThousandRows() throws Exception {
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
