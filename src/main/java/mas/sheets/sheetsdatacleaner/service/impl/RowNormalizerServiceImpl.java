package mas.sheets.sheetsdatacleaner.service.impl;

import com.ibm.icu.text.Transliterator;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RowNormalizerServiceImpl implements RowNormalizerService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCT_KEEP_SELECTED = Pattern.compile("\\p{Punct}&&[^@._/#&-]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\p{IsAlphabetic}\\d\\s@._/#&-]");
    private static final Pattern GMAIL_PLUS = Pattern.compile("\\+[^@]+$");
    private static final Pattern URL_PROTOCOL = Pattern.compile("^(https?://)?(www\\.)?");
    private static final Pattern URL_TRAIL_SLASH = Pattern.compile("/+$");
    private static final Pattern URL_PATTERN = Pattern.compile("^[a-z][a-z0-9+.-]*://.*|\\w+\\.\\w+.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_DIGITS = Pattern.compile("\\D");
    private static final Pattern FRACTION_ONE_HALF = Pattern.compile("^(½|1/2|50 ?%)$");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final List<DateTimeFormatter> DATE_FMT = List.of(
            DateTimeFormatter.ofPattern("dd.MM.yy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    );

    private static final int PARALLEL_THRESHOLD = 10_000;

    private static final EmailValidator EMAIL_VALIDATOR = EmailValidator.getInstance(false, false);

    private static final Set<String> TAGGABLE_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "yandex.ru", "fastmail.com"
    );

    private static final ThreadLocal<Transliterator> LATIN = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    );

    @Override
    public List<String> normalizeRows(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return (rows.size() > PARALLEL_THRESHOLD ? rows.parallelStream() : rows.stream())
                .map(this::normalizeRow)
                .filter(s -> !s.isBlank() && s.length() >= 3)
                .toList();
    }

    private String normalizeRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return "";
        }
        return cells.stream()
                .map(this::normalizeCell)
                .filter(s -> s.length() >= 2)
                .collect(Collectors.joining(" | "));
    }

    private String normalizeCell(String value) {
        if (value == null) {
            return "";
        }
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .trim();
        if (text.isEmpty()) {
            return "";
        }
        if (isEmail(text)) {
            return normalizeEmail(text);
        }
        if (isUrl(text)) {
            return normalizeUrl(text);
        }
        if (isPhone(text)) {
            return normalizePhone(text);
        }
        if (isDate(text)) {
            return normalizeDate(text);
        }
        if (isFraction(text)) {
            return "0.5";
        }
        return normalizeText(text);
    }

    private String normalizeText(String text) {
        text = LATIN.get().transliterate(text);
        text = PUNCT_KEEP_SELECTED.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = NON_ASCII.matcher(text).replaceAll("");
        return text.trim();
    }

    private boolean isEmail(String v) {
        int at = v.lastIndexOf('@');
        if (at <= 0 || at == v.length() - 1) {
            return false;
        }
        String local = v.substring(0, at);
        String domain;
        try {
            domain = IDN.toASCII(v.substring(at + 1));
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return EMAIL_VALIDATOR.isValid(local + '@' + domain);
    }

    private String normalizeEmail(String email) {
        int at = email.lastIndexOf('@');
        String local = email.substring(0, at);
        String domain = IDN.toASCII(email.substring(at + 1).toLowerCase(Locale.ROOT));
        if ("googlemail.com".equals(domain)) {
            domain = "gmail.com";
        }
        if (TAGGABLE_DOMAINS.contains(domain)) {
            local = local.replace(".", "");
            local = GMAIL_PLUS.matcher(local).replaceAll("");
        }
        return local + '@' + domain;
    }

    private boolean isUrl(String v) {
        return URL_PATTERN.matcher(v).matches();
    }

    private String normalizeUrl(String url) {
        url = URL_PROTOCOL.matcher(url).replaceFirst("");
        url = URL_TRAIL_SLASH.matcher(url).replaceAll("");
        return url;
    }

    private boolean isPhone(String v) {
        String digits = PHONE_DIGITS.matcher(v).replaceAll("");
        return digits.length() >= 8;
    }

    private String normalizePhone(String v) {
        String digits = PHONE_DIGITS.matcher(v).replaceAll("");
        if (digits.startsWith("8") && digits.length() == 11) {
            digits = "7" + digits.substring(1);
        }
        if (!digits.startsWith("+")) {
            digits = '+' + digits;
        }
        return digits;
    }

    private boolean isDate(String v) {
        return DATE_FMT.stream().anyMatch(fmt -> {
            try {
                LocalDate.parse(v, fmt);
                return true;
            } catch (DateTimeParseException ex) {
                return false;
            }
        });
    }

    private String normalizeDate(String v) {
        for (DateTimeFormatter fmt : DATE_FMT) {
            try {
                return LocalDate.parse(v, fmt).format(ISO);
            } catch (DateTimeParseException ignored) {
            }
        }
        return v;
    }

    private boolean isFraction(String v) {
        return FRACTION_ONE_HALF.matcher(v).matches();
    }
}
