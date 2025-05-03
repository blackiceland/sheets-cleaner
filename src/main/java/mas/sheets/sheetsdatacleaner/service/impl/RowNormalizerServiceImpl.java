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

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}&&[^@+_#/]]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\p{IsAlphabetic}\\d\\s@._+\\-#/]");
    private static final Pattern GMAIL_PLUS = Pattern.compile("\\+.*$");

    private static final int PARALLEL_THRESHOLD = 10_000;

    private static final EmailValidator EMAIL_VALIDATOR = EmailValidator.getInstance(false, false);

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

    @Override
    public List<String> normalizeRows(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        if (rows.size() > PARALLEL_THRESHOLD) {
            return rows.parallelStream()
                    .map(this::normalizeRow)
                    .toList();
        } else {
            return rows.stream()
                    .map(this::normalizeRow)
                    .toList();
        }
    }

    private String normalizeRow(List<String> rowCells) {
        if (rowCells == null || rowCells.isEmpty()) {
            return "";
        }

        return rowCells.stream()
                .map(this::normalizeCell)
                .filter(cell -> !cell.isEmpty())
                .collect(Collectors.joining(" | "));
    }

    private String normalizeCell(String cellValue) {
        if (cellValue == null) {
            return "";
        }

        String text = Normalizer.normalize(cellValue, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("\u00A0", " ")
                .trim();

        if (text.isEmpty()) {
            return "";
        }

        if (isEmail(text)) {
            return normalizeEmail(text);
        }

        return normalizeText(text);
    }

    private String normalizeText(String text) {
        text = LATIN.get().transliterate(text);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = PUNCTUATION.matcher(text).replaceAll("");
        text = NON_ASCII.matcher(text).replaceAll("");
        return text.trim();
    }

    private boolean isEmail(String value) {
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) return false;

        String localPart = value.substring(0, at);
        String domainPart = value.substring(at + 1);

        try {
            domainPart = IDN.toASCII(domainPart);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        return EMAIL_VALIDATOR.isValid(localPart + '@' + domainPart);
    }

    private String normalizeEmail(String email) {
        int at = email.lastIndexOf('@');
        String localPart = email.substring(0, at);
        String domainPart = IDN.toASCII(email.substring(at + 1).toLowerCase(Locale.ROOT));

        if (TAGGABLE_DOMAINS.contains(domainPart)) {
            localPart = localPart.replace(".", "");
            localPart = GMAIL_PLUS.matcher(localPart).replaceAll("");
        }

        return localPart + "@" + domainPart;
    }
}
