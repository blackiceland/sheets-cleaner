package mas.sheets.sheetsdatacleaner.service.impl;

import com.ibm.icu.text.Transliterator;
import lombok.extern.slf4j.Slf4j;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RowNormalizerServiceImpl implements RowNormalizerService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}&&[^@]]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\p{IsAlphabetic}\\d\\s@._+-]");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final Pattern EMAIL_PLUS = Pattern.compile("\\+[^@]*");

    private static final List<String> DOT_IGNORING_DOMAINS = List.of(
            "gmail.com", "googlemail.com"
    );

    private static final int PARALLEL_THRESHOLD = 10_000;

    private final Transliterator toLatinTransliterator =
            Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC");

    public List<String> normalizeRows(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            log.debug("Received empty rows list for normalization");
            return Collections.emptyList();
        }

        log.info("Starting normalization of {} rows", rows.size());
        long startTime = System.currentTimeMillis();

        List<String> result;

        if (rows.size() > PARALLEL_THRESHOLD) {
            log.info("Using parallel stream for processing {} rows", rows.size());
            result = rows.parallelStream()
                    .map(this::normalizeRow)
                    .toList();
        } else {
            result = rows.stream()
                    .map(this::normalizeRow)
                    .toList();
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("Normalization completed in {}ms. Processed {} rows", elapsedTime, rows.size());

        return result;
    }

    private String normalizeRow(List<String> rowCells) {
        if (rowCells == null || rowCells.isEmpty()) {
            log.trace("Encountered empty row");
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
            log.debug("Processing email: {}", maskEmail(text));
            return normalizeEmail(text);
        }

        text = normalizeText(text);
        return text;
    }

    private String normalizeText(String text) {
        log.trace("Normalizing text: {}", text.substring(0, Math.min(20, text.length())) + (text.length() > 20 ? "..." : ""));

        text = toLatinTransliterator.transliterate(text);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = PUNCTUATION.matcher(text).replaceAll("");
        text = NON_ASCII.matcher(text).replaceAll("");

        return text.trim();
    }

    private boolean isEmail(String value) {
        return EMAIL_PATTERN.matcher(value).matches();
    }

    private String normalizeEmail(String email) {
        String[] parts = email.split("@", 2);

        if (parts.length != 2) {
            log.warn("Invalid email format detected: {}", maskEmail(email));
            return email;
        }

        String localPart = parts[0];
        String domainPart = parts[1].toLowerCase(Locale.ROOT);

        String preNormalizedLocal = localPart;

        if (DOT_IGNORING_DOMAINS.contains(domainPart)) {
            localPart = localPart.replace(".", "");
            localPart = EMAIL_PLUS.matcher(localPart).replaceAll("");
        }

        if (!preNormalizedLocal.equals(localPart)) {
            log.debug("Email local part normalized from: {} to: {}",
                    maskEmailPart(preNormalizedLocal), maskEmailPart(localPart));
        }

        String normalizedEmail = localPart + "@" + domainPart;

        if (!email.equals(normalizedEmail)) {
            log.debug("Email normalized: {} -> {}",
                    maskEmail(email), maskEmail(normalizedEmail));
        }

        return normalizedEmail;
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }

        String[] parts = email.split("@", 2);
        if (parts.length != 2) {
            return email;
        }

        return maskEmailPart(parts[0]) + "@" + parts[1];
    }

    private String maskEmailPart(String localPart) {
        if (localPart == null || localPart.length() <= 2) {
            return localPart;
        }

        return localPart.charAt(0) + "***" +
                (localPart.length() > 3 ? localPart.charAt(localPart.length() - 1) : "");
    }
}