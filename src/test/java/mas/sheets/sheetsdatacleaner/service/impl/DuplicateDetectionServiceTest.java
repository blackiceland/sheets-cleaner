package mas.sheets.sheetsdatacleaner.service.impl;

import mas.sheets.sheetsdatacleaner.dto.request.DuplicateMatchRequest;
import mas.sheets.sheetsdatacleaner.dto.response.DuplicateMatchResponse;
import mas.sheets.sheetsdatacleaner.enums.ClusterKind;
import mas.sheets.sheetsdatacleaner.model.IndexPair;
import mas.sheets.sheetsdatacleaner.model.RowMeta;
import mas.sheets.sheetsdatacleaner.service.DuplicateDetectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class DuplicateDetectionServiceTest {

    private static final int PORT = 5000;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> similarity =
            new GenericContainer<>("similarity:0.3.2")
                    .withExposedPorts(PORT)
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

    @Autowired
    DuplicateDetectionService service;

    @ParameterizedTest
    @MethodSource("provideTestRows")
    @DisplayName("detects duplicates using real similarity container")
    void shouldDetectDuplicatesWithContainer(List<List<String>> rows) {
        DuplicateMatchResponse resp = service.findDuplicates(new DuplicateMatchRequest(rows, false));

        Set<IndexPair> expectedConfirmed = Set.of(
                IndexPair.of(70, 71),   // даты 15.01.23 ↔ 15 .01.2023
                IndexPair.of(102, 103), // url / e-mail
                IndexPair.of(106, 107)  // «hello world» ↔ «HELLOWORLD»
        );

        Set<IndexPair> expectedCandidates = Set.of(
                IndexPair.of(66, 68),
                IndexPair.of(137, 139),
                IndexPair.of(81, 83),
                IndexPair.of(16, 17)
        );

        assertThat(resp.confirmed()).containsAll(expectedConfirmed);
        assertThat(resp.candidates()).containsAll(expectedCandidates);
    }

    private static Stream<List<List<String>>> provideTestRows() {
        return Stream.of(
                List.of(
                        List.of("anton", "markov", ""), // 0
                        List.of("markov", "anton", ""), // 1
                        List.of("anton markov", "antonmarkov@gmail.com", ""), // 2
                        List.of("a. markov", "anton+dev@gmail.com", ""), // 3
                        List.of("résumé", "façade", "crème brûlée"), // 4
                        List.of("张伟", "mhmd", "Иван Иванов"), // 5
                        List.of("", "", ""), // 6
                        List.of("a b", "a b", "c d"), // 7
                        List.of("aleksei petrov", "aleksei.petrov@mail.ru", ""), // 8
                        List.of("a petrov", "aleksei.petrov+test@mail.ru", ""), // 9
                        List.of("ул. Ленина, 15", "lenina street 15", "moscow"), // 10
                        List.of("улица Ленина, д. 15", "15 lenina", "msk"), // 11
                        List.of("Москва", "Moscow", ""), // 12
                        List.of("москва", "moscow", "russia"), // 13
                        List.of("john", "smith", ""), // 14
                        List.of("j. smith", "", ""), // 15
                        List.of("ivan ivanov", "", "1985"), // 16
                        List.of("ivanov ivan", "", "85"), // 17
                        List.of("", "", "no duplicates here"), // 18
                        List.of("completely", "different", "row"), // 19
                        List.of("anton markov", "", ""), // 20
                        List.of("markov anton", "", ""), // 21
                        List.of("alex petrov", "moskovskaya 12", "01.01.1990"), // 22
                        List.of("sergey petrov", "tverskaya 8", "02.02.1985"), // 23
                        List.of("ivan petrov", "ivanov petr", ""), // 24

                        List.of("Michael Brown", "mbrown@example.com", ""), // 25
                        List.of("Mike Brown", "michael.brown@example.com", ""), // 26
                        List.of("123-456-7890", "", ""), // 27
                        List.of("+1 (123) 456-7890", "", ""), // 28
                        List.of("Robert Smith", "", "1977-05-15"), // 29
                        List.of("Bob Smith", "", "15.05.1977"), // 30
                        List.of("Johnson & Johnson Co.", "", ""), // 31
                        List.of("Johnson and Johnson Company", "", ""), // 32
                        List.of("San Francisco, CA", "", "USA"), // 33
                        List.of("SF, California", "", "United States"), // 34
                        List.of("software engineer", "5 years experience", ""), // 35
                        List.of("senior software developer", "5+ yrs exp", ""), // 36
                        List.of("maria.garcia@gmail.com", "", ""), // 37
                        List.of("m.garcia+work@gmail.com", "", ""), // 38
                        List.of("202-555-0123", "Washington DC", ""), // 39
                        List.of("(202) 555-0123", "Washington, D.C.", ""), // 40
                        List.of("St. Petersburg", "Russia", ""), // 41
                        List.of("Saint-Petersburg", "RU", ""), // 42
                        List.of("Dr. William Jones", "MD", "Cardiology"), // 43
                        List.of("William Jones, M.D.", "Cardiologist", ""), // 44
                        List.of("100 Main St", "Apt 3B", "New York, NY"), // 45
                        List.of("100 Main Street", "Apartment 3B", "NYC"), // 46
                        List.of("Project Manager", "IT Department", "2010-2015"), // 47
                        List.of("PM", "Information Technology", "2010-15"), // 48
                        List.of("Apple Inc.", "", "Technology"), // 49
                        List.of("Apple Incorporated", "", "Tech"), // 50
                        // Существующие кейсы
                        List.of("Apple Inc.", "", "Technology"), // 49
                        List.of("Apple Incorporated", "", "Tech"), // 50

                        // 50 новых тестовых кейсов

                        // Группа 1: Вариации имен и контактов с различной степенью сходства
                        List.of("Thomas J. Anderson", "tanderson@matrix.com", ""), // 51
                        List.of("T. Anderson", "neo@matrix.com", ""), // 52
                        List.of("Thomas Anderson", "the.one@matrix.com", ""), // 53
                        List.of("Neo", "thomas.anderson@matrix.com", ""), // 54

                        // Группа 2: Очень короткие строки и аббревиатуры
                        List.of("IBM", "International Business Machines", ""), // 55
                        List.of("I.B.M.", "Int'l Business Machines", ""), // 56
                        List.of("FBI", "Federal Bureau of Investigation", ""), // 57
                        List.of("F.B.I.", "Fed Bureau of Inv", ""), // 58

                        // Группа 3: Числовые последовательности с различными форматами
                        List.of("12345678", "", ""), // 59
                        List.of("123-456-78", "", ""), // 60
                        List.of("1234 5678", "", ""), // 61
                        List.of("12-34-56-78", "", ""), // 62

                        // Группа 4: Адреса с разным форматированием и сокращениями
                        List.of("123 Main Street, Apartment 45", "New York", "NY 10001"), // 63
                        List.of("123 Main St., Apt. 45", "NYC", "New York 10001"), // 64
                        List.of("123 Main St, #45", "New York City", "NY"), // 65
                        List.of("Apt 45, 123 Main Street", "Manhattan, NY", "10001"), // 66

                        // Группа 5: Даты в различных форматах
                        List.of("January 15, 2023", "", ""), // 67
                        List.of("15/01/2023", "", ""), // 68
                        List.of("2023-01-15", "", ""), // 69
                        List.of("15.01.23", "", ""), // 70

                        // Группа 6: Похожие строки с ошибками набора и транслитерацией
                        List.of("Dostoyevsky", "Russian author", ""), // 71
                        List.of("Dostoevski", "Russian writer", ""), // 72
                        List.of("Dostoyevskiy", "Russian novelist", ""), // 73
                        List.of("Fjodor Dostojevskij", "Author", ""), // 74

                        // Группа 7: Строки с пунктуацией и без неё
                        List.of("Hello, World!", "", ""), // 75
                        List.of("Hello World", "", ""), // 76
                        List.of("Hello:World", "", ""), // 77
                        List.of("Hello-World", "", ""), // 78

                        // Группа 8: Записи с пропущенными/добавленными словами
                        List.of("The Lord of the Rings", "J.R.R. Tolkien", "Fantasy"), // 79
                        List.of("Lord of the Rings", "Tolkien", ""), // 80
                        List.of("The Lord of Rings", "J.R.R. Tolkien", ""), // 81
                        List.of("LOTR", "J.R.R. Tolkien's", "Fantasy novel"), // 82

                        // Группа 9: Идентификаторы и коды продуктов
                        List.of("Product ID: ABC-123-XYZ", "", ""), // 83
                        List.of("ABC123XYZ", "", ""), // 84
                        List.of("ABC 123 XYZ", "", ""), // 85
                        List.of("ABC/123/XYZ", "", ""), // 86

                        // Группа 10: Записи с перестановкой слов
                        List.of("John Smith from New York", "", ""), // 87
                        List.of("Smith, John - New York", "", ""), // 88
                        List.of("New York resident John Smith", "", ""), // 89
                        List.of("Smith John (NY)", "", ""), // 90

                        // Группа 11: Многоязычные вариации одного и того же
                        List.of("Tokyo", "Japan", ""), // 91
                        List.of("東京", "日本", ""), // 92
                        List.of("Tokio", "Japón", ""), // 93
                        List.of("トーキョー", "ジャパン", ""), // 94

                        // Группа 12: Сильно различающиеся форматы номеров телефонов
                        List.of("+1 (555) 123-4567", "", ""), // 95
                        List.of("15551234567", "", ""), // 96
                        List.of("555.123.4567", "", ""), // 97
                        List.of("tel:+1-555-123-4567", "", ""), // 98

                        // Группа 13: Похожие URL и email-адреса
                        List.of("https://www.example.com", "", ""), // 99
                        List.of("http://example.com", "", ""), // 100
                        List.of("www.example.com", "", ""), // 101
                        List.of("example.com/index.html", "", ""), // 102

                        // Группа 14: Строки с разным регистром и пробелами
                        List.of("HELLOWORLD", "", ""), // 103
                        List.of("hello world", "", ""), // 104
                        List.of("Hello  World", "", ""), // 105
                        List.of("hElLo WoRlD", "", ""), // 106

                        // Группа 15: Семантически похожие, но лексически разные строки
                        List.of("Software Developer with Java experience", "", ""), // 107
                        List.of("Java Programmer", "", ""), // 108
                        List.of("Java Developer", "", ""), // 109
                        List.of("Software Engineer (Java)", "", ""), // 110

                        // Группа 16: Строки с HTML-тегами и спецсимволами
                        List.of("<strong>Important</strong> notice", "", ""), // 111
                        List.of("**Important** notice", "", ""), // 112
                        List.of("Important notice", "", ""), // 113
                        List.of("&lt;strong&gt;Important&lt;/strong&gt; notice", "", ""), // 114

                        // Группа 17: Валюты и числовые значения в разных форматах
                        List.of("$1,234.56", "", ""), // 115
                        List.of("1234.56 USD", "", ""), // 116
                        List.of("USD 1,234.56", "", ""), // 117
                        List.of("1,234 dollars and 56 cents", "", ""), // 118

                        // Группа 18: Версии программного обеспечения
                        List.of("Windows 11 Pro", "", ""), // 119
                        List.of("Win11 Professional", "", ""), // 120
                        List.of("Windows11 Pro Edition", "", ""), // 121
                        List.of("MS Windows 11", "", ""), // 122

                        // Группа 19: Почти идентичные идентификаторы с минимальными отличиями
                        List.of("ID: a1b2c3d4e5f6", "", ""), // 123
                        List.of("ID: a1b2c3d4e5f7", "", ""), // 124
                        List.of("a1b2c3d4e5f6", "", ""), // 125
                        List.of("A1B2C3D4E5F6", "", ""), // 126

                        // Группа 20: Строки с множеством пробельных символов
                        List.of("This   has   many   spaces", "", ""), // 127
                        List.of("This has many spaces", "", ""), // 128
                        List.of("This\thas\ttabs", "", ""), // 129
                        List.of("This\nhas\nnewlines", "", ""), // 130

                        // Группа 21: Строки, отличающиеся специальными символами
                        List.of("Special chars: !@#$%^&*()", "", ""), // 131
                        List.of("Special chars: !@#$%^&*[]", "", ""), // 132
                        List.of("No special chars", "", ""), // 133
                        List.of("!@#$%^&*()", "", ""), // 134

                        // Группа 22: Строки с опечатками и транспозициями
                        List.of("Definitely correct", "", ""), // 135
                        List.of("Definately correct", "", ""), // 136
                        List.of("Defintely correct", "", ""), // 137
                        List.of("Definitely corect", "", ""), // 138

                        // Группа 23: Рациональные числа в разных форматах
                        List.of("½", "", ""), // 139
                        List.of("1/2", "", ""), // 140
                        List.of("0.5", "", ""), // 141
                        List.of("50%", "", ""), // 142

                        // Группа 24: Очень длинные похожие строки
                        List.of("This is a very long string that contains many words and should test the algorithm's ability to handle lengthy inputs with high similarity but some differences at the end", "", ""), // 143
                        List.of("This is a very long string that contains many words and should test the algorithm's ability to handle lengthy inputs with high similarity but different ending", "", ""), // 144
                        List.of("This is a very long string that contains many words and should test the algorithm's capabilities with extensive text that is somewhat similar", "", ""), // 145
                        List.of("This is a completely different long string that should not match the others despite having similar length and structure", "", ""), // 146

                        // Группа 25: Пустые строки и строки только с пробельными символами
                        List.of("", "", ""), // 147
                        List.of(" ", "", ""), // 148
                        List.of("\t", "", ""), // 149
                        List.of("\n", "", ""),
                        // Группа 26: Координаты в различных форматах
                        List.of("40.7128° N, 74.0060° W", "", ""), // 151
                        List.of("40.7128N 74.0060W", "", ""), // 152
                        List.of("40°42'46.2\"N 74°00'21.6\"W", "", ""), // 153
                        List.of("40.7128, -74.0060", "", ""), // 154

// Группа 27: Номера кредитных карт с маскированием
                        List.of("XXXX-XXXX-XXXX-1234", "Visa", ""), // 155
                        List.of("**** **** **** 1234", "Visa Card", ""), // 156
                        List.of("Visa ending in 1234", "", ""), // 157
                        List.of("Card: XXXXXXXXXXXX1234", "", ""), // 158

// Группа 28: Медицинские термины и их сокращения
                        List.of("Myocardial Infarction", "Heart Attack", ""), // 159
                        List.of("MI", "Heart Attack", ""), // 160
                        List.of("Acute Myocardial Infarction", "", ""), // 161
                        List.of("AMI", "Cardiac Event", ""), // 162

// Группа 29: ISBN в различных форматах
                        List.of("ISBN: 978-3-16-148410-0", "", ""), // 163
                        List.of("978-3-16-148410-0", "", ""), // 164
                        List.of("978 3 16 148410 0", "", ""), // 165
                        List.of("9783161484100", "", ""), // 166

// Группа 30: Смешанные типы и единицы измерения
                        List.of("100 km/h", "Maximum Speed", ""), // 167
                        List.of("100 kilometers per hour", "", ""), // 168
                        List.of("62.14 mph", "", ""), // 169
                        List.of("27.78 m/s", "", ""), // 170

// Группа 31: Варианты технических идентификаторов
                        List.of("00:1B:44:11:3A:B7", "MAC Address", ""), // 171
                        List.of("00-1B-44-11-3A-B7", "", ""), // 172
                        List.of("001B44113AB7", "", ""), // 173
                        List.of("00.1B.44.11.3A.B7", "", ""), // 174

// Группа 32: Форматы времени
                        List.of("15:30:45", "", ""), // 175
                        List.of("3:30:45 PM", "", ""), // 176
                        List.of("15:30", "", ""), // 177
                        List.of("3:30 PM", "", ""), // 178

// Группа 33: Имена файлов и пути
                        List.of("C:\\Users\\John\\Documents\\report.docx", "", ""), // 179
                        List.of("/home/john/documents/report.docx", "", ""), // 180
                        List.of("report.docx", "", ""), // 181
                        List.of("./documents/report.docx", "", ""), // 182

// Группа 34: Сложные юридические наименования
                        List.of("Smith & Associates, LLC", "", ""), // 183
                        List.of("Smith and Associates Limited Liability Company", "", ""), // 184
                        List.of("Smith & Associates", "", ""), // 185
                        List.of("Smith Associates LLC", "", ""), // 186

// Группа 35: Хеш-суммы в разных алгоритмах
                        List.of("MD5: e10adc3949ba59abbe56e057f20f883e", "", ""), // 187
                        List.of("SHA-1: f7c3bc1d808e04732adf679965ccc34ca7ae3441", "", ""), // 188
                        List.of("e10adc3949ba59abbe56e057f20f883e", "", ""), // 189
                        List.of("f7c3bc1d808e04732adf679965ccc34ca7ae3441", "", ""), // 190

// Группа 36: Социальные сети и обращения
                        List.of("@john_doe", "Twitter", ""), // 191
                        List.of("twitter.com/john_doe", "", ""), // 192
                        List.of("John Doe (Twitter: @john_doe)", "", ""), // 193
                        List.of("John Doe on Twitter", "", ""), // 194

// Группа 37: Разные типы идентификаторов и серийные номера
                        List.of("SN: AX-9981-Z4", "", ""), // 195
                        List.of("Serial: AX9981Z4", "", ""), // 196
                        List.of("AX-9981-Z4", "", ""), // 197
                        List.of("AX9981Z4", "", ""), // 198

// Группа 38: Технологические стеки
                        List.of("Python, Django, PostgreSQL", "", ""), // 199
                        List.of("Python/Django/PostgreSQL", "", ""), // 200
                        List.of("Backend: Python, Django, DB: PostgreSQL", "", ""), // 201
                        List.of("Python + Django + PostgreSQL Stack", "", ""), // 202

// Группа 39: Научные обозначения и химические формулы
                        List.of("H₂O", "", ""), // 203
                        List.of("H2O", "Water", ""), // 204
                        List.of("Dihydrogen Monoxide", "", ""), // 205
                        List.of("Water (H2O)", "", ""), // 206

// Группа 40: Музыкальные нотации и ключи
                        List.of("C# minor", "", ""), // 207
                        List.of("C-sharp minor", "", ""), // 208
                        List.of("Cis-moll", "", ""), // 209
                        List.of("C# m", "", ""), // 210

// Группа 41: Пароли и зашифрованные данные
                        List.of("P@ssw0rd123!", "", ""), // 211
                        List.of("P@ssw0rd123", "", ""), // 212
                        List.of("********", "Password", ""), // 213
                        List.of("[Encrypted]", "Password", ""), // 214

// Группа 42: Временные периоды
                        List.of("2020-2023", "Project Duration", ""), // 215
                        List.of("2020 to 2023", "", ""), // 216
                        List.of("2020—2023", "", ""), // 217
                        List.of("From 2020 until 2023", "", ""), // 218

// Группа 43: Версии API и протоколов
                        List.of("HTTP/1.1", "", ""), // 219
                        List.of("HTTP 1.1", "", ""), // 220
                        List.of("Hypertext Transfer Protocol v1.1", "", ""), // 221
                        List.of("HTTP Version 1.1", "", ""), // 222

// Группа 44: Цветовые коды в различных форматах
                        List.of("#FF5733", "", ""), // 223
                        List.of("rgb(255, 87, 51)", "", ""), // 224
                        List.of("hsl(14, 100%, 60%)", "", ""), // 225
                        List.of("255, 87, 51", "Color", ""), // 226

// Группа 45: Разные форматы библиографических ссылок
                        List.of("Smith, J. (2023). The Art of Programming. Journal of Computer Science, 15(2), 145-158.", "", ""), // 227
                        List.of("Smith J. The Art of Programming // Journal of Computer Science. 2023. Vol. 15(2). P. 145-158", "", ""), // 228
                        List.of("Smith (2023) The Art of Programming", "", ""), // 229
                        List.of("Smith, J. \"The Art of Programming.\" Journal of Computer Science, vol. 15, no. 2, 2023, pp. 145-158.", "", ""), // 230// 150

                        List.of("anton markov 1"),
                        List.of("anton markov 2")
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideTestRowsProd")
    @DisplayName("duplicate detector returns clusters with FUZZY rows")
    void shouldDetectClustersWithFuzzy(List<List<String>> rows) {
        DuplicateMatchResponse resp = service.findDuplicates(new DuplicateMatchRequest(rows, false));

        // ─── confirmed (точные) ──────────────────────────────────────────────
        Set<IndexPair> expectedConfirmed = Set.of(
                IndexPair.of(0, 4)
        );

        assertThat(resp.confirmed()).containsAll(expectedConfirmed);

        List<RowMeta> meta = resp.meta();

        assertThat(meta).hasSize(6);

        long canonCnt = meta.stream()
                .filter(m -> m.kind() == ClusterKind.CANON).count();
        long exactCnt = meta.stream()
                .filter(m -> m.kind() == ClusterKind.EXACT).count();
        long fuzzyCnt = meta.stream()
                .filter(m -> m.kind() == ClusterKind.FUZZY).count();

        assertThat(canonCnt).isEqualTo(1);
        assertThat(exactCnt).isEqualTo(1);
        assertThat(fuzzyCnt).isEqualTo(4);
    }

    private static Stream<List<List<String>>> provideTestRowsProd() {
        return Stream.of(
                List.of(
                        List.of("антон марков"),            // 0
                        List.of("антон мурков"),            // 1
                        List.of("марков антон сергеевич"),  // 2
                        List.of("Марков Антон"),            // 3
                        List.of("антон    Марков"),         // 4
                        List.of("антон марковv")          // 5
                )
        );
    }

}
