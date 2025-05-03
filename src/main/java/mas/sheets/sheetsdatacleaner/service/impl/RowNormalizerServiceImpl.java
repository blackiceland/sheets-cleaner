package mas.sheets.sheetsdatacleaner.service.impl;

import com.ibm.icu.text.Transliterator;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
public class RowNormalizerServiceImpl implements RowNormalizerService {

    /* ----------- регэкспы и константы ----------- */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /**
     * вся пунктуация, кроме @, чтобы не ломать локальный email‑детектор
     */
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}&&[^@]]+");
    /**
     * оставляем буквы, цифры, пробел, @ . _ -
     */
    private static final Pattern NON_ASCII =
            Pattern.compile("[^\\p{IsAlphabetic}\\d\\s@._-]");

    private static final Pattern GMAIL_PLUS = Pattern.compile("\\+.*$");

    private static final int PARALLEL_THRESHOLD = 10_000;

    private static final EmailValidator EMAIL_VALIDATOR =
            EmailValidator.getInstance(false, false);

    private static final Set<String> TAGGABLE_DOMAINS = Set.of(
            "gmail.com",
            "googlemail.com",
            "outlook.com",
            "hotmail.com",
            "yandex.ru",
            "fastmail.com"
    );

    private static final ThreadLocal<Transliterator> LATIN =
            ThreadLocal.withInitial(() ->
                    Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC"));

    /* ------------- публичный API ------------- */

    @Override
    public List<String> normalizeRows(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();

        return (rows.size() > PARALLEL_THRESHOLD ? rows.parallelStream() : rows.stream())
                .map(this::normalizeRow)
                .toList();
    }

    /* ------------- private helpers ------------- */

    private String normalizeRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) return "";

        return cells.stream()
                .map(this::normalizeCell)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" | "));
    }

    private String normalizeCell(String value) {
        if (value == null) return "";

        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("\u00A0", " ")
                .trim();
        if (text.isEmpty()) return "";

        return isEmail(text) ? normalizeEmail(text) : normalizeText(text);
    }

    /* -------- текст -------- */

    private String normalizeText(String text) {
        text = LATIN.get().transliterate(text);            // кириллица → Latin
        text = PUNCTUATION.matcher(text).replaceAll(" ");  // пунктуация → пробел
        text = WHITESPACE.matcher(text).replaceAll(" ");   // схлопнуть пробелы
        text = NON_ASCII.matcher(text).replaceAll("");     // убрать мусор
        return text.trim();
    }

    /* -------- email -------- */

    private boolean isEmail(String v) {
        int at = v.lastIndexOf('@');
        if (at <= 0 || at == v.length() - 1) return false;

        String local = v.substring(0, at);
        String domain = v.substring(at + 1);

        try {
            domain = IDN.toASCII(domain);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        return EMAIL_VALIDATOR.isValid(local + '@' + domain);
    }

    private String normalizeEmail(String email) {
        int at = email.lastIndexOf('@');
        String local = email.substring(0, at);
        String domain = IDN.toASCII(email.substring(at + 1).toLowerCase(Locale.ROOT));

        /* unify googlemail → gmail */
        if ("googlemail.com".equals(domain)) domain = "gmail.com";

        /* Gmail‑style aliasing */
        if (TAGGABLE_DOMAINS.contains(domain)) {
            local = local.replace(".", "");
            local = GMAIL_PLUS.matcher(local).replaceAll("");
        }
        return local + '@' + domain;
    }
}
