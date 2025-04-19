package mas.sheets.sheetsdatacleaner.service.impl;

import com.ibm.icu.text.Transliterator;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
public class RowNormalizerServiceImpl implements RowNormalizerService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}&&[^@]]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\s@._+-]");
    private static final Pattern GMAIL_PLUS = Pattern.compile("\\+.*$");
    private static final int PARALLEL_THRESHOLD = 10_000;

    private final Transliterator toLatinTransliterator =
            Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC");

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

        String text = Normalizer.normalize(cellValue, Normalizer.Form.NFKC);
        text = text.toLowerCase(Locale.ROOT).replace("\u00A0", " ").trim();

        if (text.isEmpty()) {
            return "";
        }

        if (isEmail(text)) {
            return normalizeEmail(text);
        }

        text = normalizeText(text);

        return text;
    }

    private String normalizeText(String text) {
        text = toLatinTransliterator.transliterate(text);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = PUNCTUATION.matcher(text).replaceAll("");
        text = NON_ASCII.matcher(text).replaceAll("");

        return text.trim();
    }

    private boolean isEmail(String value) {
        return value.contains("@") && value.contains(".");
    }

    private String normalizeEmail(String email) {
        String[] parts = email.split("@", 2);

        if (parts.length != 2) {
            return email;
        }

        String localPart = parts[0];
        String domainPart = parts[1].toLowerCase(Locale.ROOT);

        if (domainPart.equalsIgnoreCase("gmail.com") || domainPart.equalsIgnoreCase("googlemail.com")) {
            localPart = localPart.replace(".", "");
            localPart = GMAIL_PLUS.matcher(localPart).replaceAll("");
        }

        return localPart + "@" + domainPart;
    }

}
