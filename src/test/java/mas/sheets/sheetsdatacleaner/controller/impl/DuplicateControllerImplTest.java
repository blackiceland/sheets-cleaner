package mas.sheets.sheetsdatacleaner.controller.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class DuplicateControllerImplTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private static final int PORT = 5000;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> similarity =
            new GenericContainer<>("similarity:0.3.2")
                    .withExposedPorts(PORT)
                    .withReuse(true)
                    .waitingFor(
                            Wait.forHttp("/health")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(5)));

    static {
        similarity.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("embedding.api.base-url", () -> "http://" + similarity.getHost() + ":" + similarity.getMappedPort(PORT) + "/similarity");
        r.add("resilience4j.timelimiter.instances.embeddingApi.timeoutDuration", () -> "15s");
    }

    @Test
    void emptyRows() throws Exception {
        DuplicateMatchRequest request = new DuplicateMatchRequest(List.of(), false);

        mvc.perform(post("/api/v1/sheets/duplicates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }


    @Test
    void duplicatesDetectedCorrectly() throws Exception {
        DuplicateMatchRequest request = new DuplicateMatchRequest(ROWS, false);

        byte[] resp = mvc.perform(post("/api/v1/sheets/duplicates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        DuplicateMatchResponse result = mapper.readValue(resp, DuplicateMatchResponse.class);

        assertThat(result.confirmed()).hasSize(26);
        assertThat(result.candidates()).hasSize(74);
    }

    private static final List<List<String>> ROWS = List.<List<String>>of(
            // 5 уникальных (не должны давать дубликатов)
            List.of("unique_name_001"),
            List.of("unique_name_002"),
            List.of("unique_name_003"),
            List.of("completely_different_entry_1"),
            List.of("completely_different_entry_2"),

            // Пограничные кейсы: Очень короткие имена
            List.of("Al Li"), List.of("Li Al"), List.of("A. Li"), List.of("Li, A"),

            // Пограничные кейсы: Очень длинные имена
            List.of("Friedrich Wilhelm Viktor Albert von Preußen"), List.of("Friedrich W. V. A. von Preußen"),
            List.of("Friedrich Wilhelm von Preußen"), List.of("F. W. V. A. von Preußen"),

            // Пограничные кейсы: Многочисленные пробелы/форматирование
            List.of("Emily     Jane     Roberts"), List.of("Emily Jane Roberts"),
            List.of("Emily-Jane Roberts"), List.of("Emily J. Roberts"),

            // Пограничные кейсы: Опечатки в имени
            List.of("Jennifer Lawrence"), List.of("Jenniffer Lawrence"),
            List.of("Jennipher Lawrence"), List.of("Jenifer Lawrence"),

            // Пограничные кейсы: Опечатки в фамилии
            List.of("Justin Timberlake"), List.of("Justin Timberlaek"),
            List.of("Justin Timbrelake"), List.of("Justin Timbrlake"),

            // Пограничные кейсы: Переключение между латиницей и кириллицей
            List.of("Ivan Petrov"), List.of("Иван Петров"),
            List.of("Ivan Petroff"), List.of("I. Petrov"),

            // Пограничные кейсы: Диакритические знаки в разных формах
            List.of("Zoë Saldaña"), List.of("Zoe Saldana"),
            List.of("Zoë Saldana"), List.of("Zoe Saldaña"),

            // Пограничные кейсы: Инициалы с и без точек
            List.of("J K Rowling"), List.of("J. K. Rowling"),
            List.of("JK Rowling"), List.of("J.K. Rowling"),

            // Пограничные кейсы: Разные варианты апострофов
            List.of("D'Artagnan"), List.of("D'Artagnan"),
            List.of("D'Artagnan"), List.of("D`Artagnan"),

            // Пограничные кейсы: Обратный порядок имен в разных культурах
            List.of("Kim Taeyeon"), List.of("Taeyeon Kim"),
            List.of("Kim, Taeyeon"), List.of("태연 김"),

            // Пограничные кейсы: Префиксы/суффиксы
            List.of("Dr. Jane Smith"), List.of("Jane Smith, M.D."),
            List.of("Professor Jane Smith"), List.of("Jane Smith"),

            // Пограничные кейсы: Среднее имя включено/исключено
            List.of("George Walker Bush"), List.of("George W. Bush"),
            List.of("George Bush"), List.of("G. W. Bush"),

            // Пограничные кейсы: Сокращения и формы имен
            List.of("William Jefferson Clinton"), List.of("Bill Clinton"),
            List.of("William J. Clinton"), List.of("Bill J. Clinton"),

            // Пограничные кейсы: Цифры и специальные символы
            List.of("John Smith Jr."), List.of("John Smith II"),
            List.of("John Smith 2nd"), List.of("John Smith (2)"),

            // Пограничные кейсы: Предлоги и артикли в фамилиях
            List.of("Ludwig van Beethoven"), List.of("L. v. Beethoven"),
            List.of("Ludwig van-Beethoven"), List.of("van Beethoven, Ludwig"),

            // Пограничные кейсы: Составные фамилии
            List.of("Gabriel García Márquez"), List.of("Gabriel G. Márquez"),
            List.of("G. García-Márquez"), List.of("García Márquez, Gabriel"),

            // Пограничные кейсы: Смешанный регистр
            List.of("McDONALD"), List.of("McDonald"),
            List.of("Mcdonald"), List.of("MC DONALD"),

            // Пограничные кейсы: Расширенные Unicode символы
            List.of("Søren Kierkegaard"), List.of("Soren Kierkegaard"),
            List.of("Søren Kierkegård"), List.of("S. Kierkegaard"),

            // Пограничные кейсы: Очень похожие имена разных людей
            List.of("John H. Smith"), List.of("John J. Smith"),
            List.of("John Henry Smith"), List.of("John Jacob Smith"),

            // Пограничные кейсы: Фонетически похожие
            List.of("Cathy"), List.of("Kathy"),
            List.of("Catherine"), List.of("Katherine"),

            // Пограничные кейсы: Пунктуация
            List.of("St. John"), List.of("Saint John"),
            List.of("St John"), List.of("Saint-John"),

            // Пограничные кейсы: Разные языки для одного имени
            List.of("Yuri Gagarin"), List.of("Юрий Гагарин"),
            List.of("Jurij Gagarin"), List.of("Y. Gagarin"),

            // Пограничные кейсы: HTML-сущности
            List.of("John &amp; Jane"), List.of("John & Jane"),
            List.of("John and Jane"), List.of("J&J"),

            // Пограничные кейсы: Экстремально короткие имена
            List.of("Li"), List.of("李"),
            List.of("Li, X"), List.of("X. Li")
    );

}



