package mas.sheets.sheetsdatacleaner.service;

import mas.sheets.sheetsdatacleaner.service.impl.RowNormalizerServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RowNormalizerServiceTest {

    private final RowNormalizerServiceImpl service = new RowNormalizerServiceImpl();

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
                Arrays.asList("a\tb", "a b", "c\u00A0d"),
                Arrays.asList("Aleksei Petrov", "aleksei.petrov@mail.ru"),
                Arrays.asList("A. Petrov", "aleksei.petrov+test@mail.ru")
        );

        List<String> result = service.normalizeRows(input);

        assertThat(result).containsExactly(
                "anton markov",
                "markov anton",
                "anton markov | antonmarkov@gmail.com",
                "a markov | anton@gmail.com",
                "resume | facade | creme brulee",
                "zhang wei | mhmd | ivan ivanov",
                "",
                "a b | a b | c d",
                "aleksei petrov | aleksei.petrov@mail.ru",
                "a petrov | aleksei.petrov+test@mail.ru"
        );
    }
}
