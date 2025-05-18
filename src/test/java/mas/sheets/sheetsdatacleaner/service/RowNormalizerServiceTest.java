package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.impl.RowNormalizerServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RowNormalizerServiceTest {

    private final RowNormalizerServiceImpl service = new RowNormalizerServiceImpl();

    private static List<String> values(List<RowNorm> list) {
        return list.stream().map(RowNorm::value).toList();
    }

    @Test
    void shouldNormalizeWideRangeOfInputsCorrectly() {
        List<List<String>> input = List.of(
                List.of("  Антон   Марков  "),
                List.of("Марков, Антон"),
                Arrays.asList("ANTON MARKOV", "anton.markov@gmail.com"),
                Arrays.asList("A. Марков", "a.n.t.o.n+dev@gmail.com"),
                Arrays.asList("résumé", "façade", "crème brûlée"),
                Arrays.asList("张伟", "محمد", "Иван Иванов"),
                Arrays.asList(null, "", "     "),
                Arrays.asList("a\tb", "a b", "c\u00A0d"),
                Arrays.asList("Aleksei Petrov", "aleksei.petrov@mail.ru"),
                Arrays.asList("A. Petrov", "aleksei.petrov+test@mail.ru"),

                // Names
                Arrays.asList("John Smith", "JOHN SMITH", "J. Smith"),
                Arrays.asList("O'Connor", "O`Connor", "O'Connor-Williams"),
                Arrays.asList("Mary-Anne Johnson", "Mary Anne Johnson"),
                Arrays.asList("van der Waals", "VAN DER WAALS"),

                // Addresses
                Arrays.asList("123 Main St, Apt 4B", "123 Main Street, Apartment 4B"),
                Arrays.asList("New York, NY 10001", "NY, New York 10001"),
                Arrays.asList("San-Francisco", "San Francisco", "SanFrancisco"),
                Arrays.asList("ul. Leninskaya, d. 15", "Leninskaya st., 15"),

                // Dates & numbers
                Arrays.asList("2023-01-15", "01/15/2023", "15.01.2023"),
                Arrays.asList("$1,234.56", "1234.56 USD", "1,234.56$"),
                Arrays.asList("ID: 12345-AB", "ID#12345AB", "ID: 12345 AB"),
                Arrays.asList("+1 (234) 567-8901", "+12345678901", "234-567-8901"),

                // Emails
                Arrays.asList("john.doe@example.com", "john.doe+newsletters@example.com"),
                Arrays.asList("info@компания.рф", "info@xn--80aswg.xn--p1ai"),
                Arrays.asList("user@googlemail.com", "user@gmail.com"),
                Arrays.asList("support+123@outlook.com", "support@outlook.com"),

                // Mixed case / special chars
                Arrays.asList("MiXeD CaSe TeXt", "mixed case text"),
                Arrays.asList("C++", "C#", "Java_Script"),
                Arrays.asList("alpha-beta-gamma", "alpha.beta.gamma"),
                Arrays.asList("text::with::colons", "text/with/slashes"),

                // Multilingual
                Arrays.asList("München", "Muenchen", "Munich"),
                Arrays.asList("北京市", "Beijing", "Пекин"),
                Arrays.asList("서울", "Seoul", "Сеул"),
                Arrays.asList("Straße", "Strasse", "Улица"),

                // Complex combos
                Arrays.asList("Dr. John Smith, MD", "John Smith, M.D."),
                Arrays.asList("2022 Q4 Report", "Report for Q4 2022"),
                Arrays.asList("Project #123-XYZ", "Project: 123 XYZ"),
                Arrays.asList("a/b ratio: 15%", "a:b = 15%")
        );

        List<String> result = values(service.normalizeRows(input));

        assertThat(result).containsExactly(
                "anton markov",
                "markov anton",
                "anton markov | antonmarkov@gmail.com",
                "a. markov | anton@gmail.com",
                "resume | facade | creme brulee",
                "zhang wei | mhmd | ivan ivanov",
                "a b | a b | c d",
                "aleksei petrov | aleksei.petrov@mail.ru",
                "a. petrov | aleksei.petrov+test@mail.ru",

                // Names
                "john smith | john smith | j. smith",
                "oconnor | oconnor | oconnor-williams",
                "mary-anne johnson | mary anne johnson",
                "van der waals | van der waals",

                // Addresses
                "123 main st apt 4b | 123 main street apartment 4b",
                "new york ny 10001 | ny new york 10001",
                "san-francisco | san francisco | sanfrancisco",
                "ul. leninskaya d. 15 | leninskaya st. 15",

                // Dates & numbers
                "2023-01-15 | 2023-01-15 | 15.01.2023",
                "1234.56 | 1234.56 usd | 1234.56",
                "id 12345-ab | id#12345ab | id 12345 ab",
                "+12345678901 | +12345678901 | 234-567-8901",

                // Emails
                "john.doe@example.com | john.doe+newsletters@example.com",
                "info@xn--80aqeigdi5k.xn--p1ai | info@xn--80aswg.xn--p1ai",
                "user@gmail.com | user@gmail.com",
                "support@outlook.com | support@outlook.com",

                // Special chars / case
                "mixed case text | mixed case text",
                "c++ | c# | java_script",
                "alpha-beta-gamma | alpha.beta.gamma",
                "textwithcolons | text/with/slashes",

                // Multilingual
                "munchen | muenchen | munich",
                "bei jing shi | beijing | pekin",
                "seoul | seoul | seul",
                "straße | strasse | ulica",

                // Complex combos
                "dr. john smith md | john smith m.d.",
                "2022 q4 report | report for q4 2022",
                "project #123-xyz | project 123 xyz",
                "a/b ratio 15 | ab  15"
        );
    }

    @Test
    void shouldHandleEmptyAndNullInputs() {
        assertThat(values(service.normalizeRows(null))).isEmpty();
        assertThat(values(service.normalizeRows(List.of()))).isEmpty();

        List<List<String>> nullRowsList  = Arrays.asList(null, null);
        assertThat(values(service.normalizeRows(nullRowsList))).isEmpty();

        List<List<String>> emptyRowsList = Arrays.asList(List.of(), List.of());
        assertThat(values(service.normalizeRows(emptyRowsList))).isEmpty();
    }

    @Test
    void shouldNormalizeEmailsCorrectly() {
        List<List<String>> emails = List.of(
                List.of("john.doe@gmail.com"),
                List.of("j.o.h.n.doe@gmail.com"),
                List.of("johndoe+test123@gmail.com"),
                List.of("JOHN.DOE@GMAIL.COM"),
                List.of("john.smith@googlemail.com"),
                List.of("jane-doe@yandex.ru"),
                List.of("jane.doe@yandex.ru"),
                List.of("janedoe+shopping@yandex.ru"),
                List.of("info@компания.рф"),
                List.of("user@домен.укр")
        );

        List<String> result = values(service.normalizeRows(emails));

        assertThat(result).containsExactly(
                "johndoe@gmail.com",
                "johndoe@gmail.com",
                "johndoe@gmail.com",
                "johndoe@gmail.com",
                "johnsmith@gmail.com",
                "jane-doe@yandex.ru",
                "janedoe@yandex.ru",
                "janedoe@yandex.ru",
                "info@xn--80aqeigdi5k.xn--p1ai",
                "user@xn--d1acufc.xn--j1amh"
        );
    }

    @Test
    void shouldNormalizeTextWithSpecialCharactersCorrectly() {
        List<List<String>> texts = List.of(
                List.of("C++"),
                List.of("C#"),
                List.of("JavaScript"),
                List.of("Python 3.9"),
                List.of("15% discount"),
                List.of("$299.99"),
                List.of("alpha-beta-gamma"),
                List.of("alpha_beta_gamma"),
                List.of("alpha & beta"),
                List.of("Company: \"Acme Inc.\"")
        );

        List<String> result = values(service.normalizeRows(texts));

        assertThat(result).containsExactly(
                "c++",
                "javascript",
                "python 3.9",
                "15 discount",
                "299.99",
                "alpha-beta-gamma",
                "alpha_beta_gamma",
                "alpha & beta",
                "company acme inc."
        );
    }

    @Test
    void shouldParallelizeForLargeInputs() {
        int rowCount = 15_000;
        List<List<String>> largeInput = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            largeInput.add(List.of("Row " + i, "Value " + i));
        }

        long start = System.currentTimeMillis();
        List<String> result = values(service.normalizeRows(largeInput));
        long time  = System.currentTimeMillis() - start;

        assertThat(result).hasSize(rowCount);
        assertThat(time).isLessThan(5_000);
    }
}
