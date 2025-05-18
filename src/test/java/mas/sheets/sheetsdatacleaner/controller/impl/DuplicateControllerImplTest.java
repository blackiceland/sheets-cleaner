package mas.sheets.sheetsdatacleaner.controller.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.client.EmbeddingApiClient;
import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
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
class DuplicateControllerImplTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    EmbeddingApiClient embedding;

    @Test
    void duplicatesDetectedCorrectly() throws Exception {
        when(embedding.embedBatch(anyList()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(
                        Collections.nCopies(((List<?>) inv.getArgument(0)).size(), 100.0)));

        DuplicateMatchRequest request = new DuplicateMatchRequest(ROWS);

        byte[] resp = mvc.perform(post("/api/v1/sheets/duplicates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();

        DuplicateMatchResponse result = mapper.readValue(resp, DuplicateMatchResponse.class);

        assertThat(result.confirmed()).hasSize(74);
        assertThat(result.candidates()).hasSize(27);
    }

    @Test
    void emptyRows() throws Exception {
        when(embedding.embedBatch(anyList()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(
                        Collections.nCopies(((List<?>) inv.getArgument(0)).size(), 100.0)));

        DuplicateMatchRequest request = new DuplicateMatchRequest(List.of());

        mvc.perform(post("/api/v1/sheets/duplicates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk());
    }

    private static final List<List<String>> ROWS = List.<List<String>>of(
            List.of("John Smith"), List.of("Smith John"), List.of("J. Smith"), List.of("John SMITH"),
            List.of("Robert Johnson"), List.of("Johnson Robert"), List.of("R. Johnson"), List.of("Robrt Johnson"),
            List.of("James Wilson"), List.of("Wilson James"), List.of("J. Wilson"), List.of("James Wilson Jr."),
            List.of("Sarah O'Connor"), List.of("O'Connor Sarah"), List.of("S. O'Connor"), List.of("Sarah OConnor"),
            List.of("Nicolás García"), List.of("García Nicolás"), List.of("N. García"), List.of("Nicolas Garcia"),
            List.of("Steven White"), List.of("White Steven"), List.of("S. White"), List.of("Steve White"),
            List.of("Elizabeth Davis"), List.of("Davis Elizabeth"), List.of("E. Davis"), List.of("Elizabeth.Davis"),
            List.of("François Dupont"), List.of("Dupont François"), List.of("F. Dupont"), List.of("Francois DUPONT"),
            List.of("Christopher Jackson"), List.of("Jackson Christopher"), List.of("C. Jackson"), List.of("Chris Jackson"),
            List.of("Albert Davidson"), List.of("Davidson Albert"), List.of("A. Davidson"), List.of("Al Davidson"),
            List.of("Alex Smith"), List.of("Smith Alex"), List.of("A. Smith"), List.of("Alex Smith (CEO)"),
            List.of("Richard Thompson"), List.of("Thompson Richard"), List.of("R. Thompson"), List.of("Rick Thompson"),
            List.of("Michael Robinson"), List.of("Robinson Michael"), List.of("M. Robinson"), List.of("Mike Robinson"),
            List.of("Thomas Anderson"), List.of("Anderson Thomas"), List.of("T. Anderson"), List.of("Tom Anderson"),
            List.of("Jean-Pierre de la Fontaine"), List.of("de la Fontaine Jean-Pierre"), List.of("J-P. de la Fontaine"), List.of("Jean Pierre Fontaine"),
            List.of("Catherine Wilson"), List.of("Wilson Catherine"), List.of("C. Wilson"), List.of("Kathryn Wilson"),
            List.of("María López"), List.of("López María"), List.of("M. López"), List.of("Maria Lopez"),
            List.of("Mark Walker"), List.of("Walker Mark"), List.of("M. Walker"), List.of("Mark Walker Sr."),
            List.of("Daniel Harris"), List.of("Harris Daniel"), List.of("D. Harris"), List.of("Dan Harris"),
            List.of("Jessica Moore"), List.of("Moore Jessica"), List.of("J. Moore"), List.of("Jessica Moore PhD"),
            List.of("Karen Lee"), List.of("Lee Karen"), List.of("K. Lee"), List.of("Karen   Lee"),
            List.of("Donna Young"), List.of("Young Donna"), List.of("D. Young"), List.of("Donna  Young"),
            List.of("Barbara King"), List.of("King Barbara"), List.of("B. King"), List.of("Barbra King"),
            List.of("Paul Clark"), List.of("Clark Paul"), List.of("P. Clark"), List.of("Paul Clark II"),

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



