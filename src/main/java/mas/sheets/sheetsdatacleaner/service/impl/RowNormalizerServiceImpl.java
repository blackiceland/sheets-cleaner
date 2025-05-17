package mas.sheets.sheetsdatacleaner.service.impl;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.ibm.icu.text.Transliterator;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.service.RowNormalizerService;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.stereotype.Service;

import java.net.IDN;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class RowNormalizerServiceImpl implements RowNormalizerService {

    /* ──────────────── RegExp & const ─────────────────────────────── */

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCT_KEEP_SELECTED = Pattern.compile("\\p{Punct}&&[^@._/#&+\\-]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\p{IsAlphabetic}\\d\\s@._/#&+\\-]");
    private static final Pattern GMAIL_PLUS = Pattern.compile("\\+[^@]+$");

    private static final Pattern URL_PROTOCOL = Pattern.compile("^(https?://)?(www\\.)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_TRAIL_SLASH = Pattern.compile("/+$");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^[a-z][a-z0-9+.-]*://.*|\\w+\\.\\w+.*", Pattern.CASE_INSENSITIVE);

    private static final Pattern FRACTION_ONE_HALF = Pattern.compile("^(½|1/2|50 ?%)$");
    private static final Pattern PHONE_DIGITS = Pattern.compile("\\D");

    /* ──────────────── Date formatters ────────────────────────────── */

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;


    private static final List<DateTimeFormatter> DATE_FMT = List.of(
            DateTimeFormatter.ofPattern("dd.MM.yy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("d-MMM-yy", new Locale("ru"))
    );

    /* ──────────────── misc ──────────────────────────────────────── */

    private static final int PARALLEL_THRESHOLD = 10_000;

    private static final EmailValidator EMAIL_VALIDATOR = EmailValidator.getInstance(false, false);
    private static final Set<String> TAGGABLE_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "outlook.com",
            "hotmail.com", "yandex.ru", "fastmail.com"
    );

    private static final ThreadLocal<Transliterator> LATIN = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    );

    private final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    /* ──────────────── public API ─────────────────────────────────── */

    @Override
    public List<RowNorm> normalizeRows(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();

        IntStream rng = rows.size() > PARALLEL_THRESHOLD
                ? IntStream.range(0, rows.size()).parallel()
                : IntStream.range(0, rows.size());

        return rng
                .mapToObj(i -> {
                    String norm = normalizeRow(rows.get(i));
                    return (norm.isBlank() || norm.length() < 3)
                            ? null
                            : new RowNorm(i, norm);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /* ──────────────── helpers ───────────────────────────────────── */

    private String normalizeRow(List<String> cells) {
        if (cells == null || cells.isEmpty()) return "";
        return cells.stream()
                .map(this::normalizeCell)
                .filter(s -> s.length() >= 2)
                .collect(Collectors.joining(" | "));
    }

    private String normalizeCell(String value) {
        if (value == null) return "";

        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .trim();
        if (text.isEmpty()) return "";

        if (isEmail(text)) return normalizeEmail(text);
        if (isUrl(text)) return normalizeUrl(text);

        String date = tryNormalizeDate(text);
        if (date != null) return date;

        String phone = tryNormalizePhone(text);
        if (phone != null) return phone;

        if (isFraction(text)) return "0.5";

        return normalizeText(text);
    }

    /* ──────────────── plain text ────────────────────────────────── */

    private String normalizeText(String text) {
        text = LATIN.get().transliterate(text);
        text = PUNCT_KEEP_SELECTED.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = NON_ASCII.matcher(text).replaceAll("");
        return text.trim();
    }

    /* ──────────────── email ─────────────────────────────────────── */

    private boolean isEmail(String v) {
        int at = v.lastIndexOf('@');
        if (at <= 0 || at == v.length() - 1) return false;
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
        if ("googlemail.com".equals(domain)) domain = "gmail.com";
        if (TAGGABLE_DOMAINS.contains(domain)) {
            local = local.replace(".", "");
            local = GMAIL_PLUS.matcher(local).replaceAll("");
        }
        return local + '@' + domain;
    }

    /* ──────────────── url ───────────────────────────────────────── */

    private boolean isUrl(String v) {
        return URL_PATTERN.matcher(v).matches();
    }

    /**
     * домен (+порт) и первые два сегмента пути, query/anchor отбрасываем
     */
    private String normalizeUrl(String url) {
        url = URL_PROTOCOL.matcher(url).replaceFirst("");

        int cut = url.indexOf('#');
        if (cut >= 0) url = url.substring(0, cut);
        cut = url.indexOf('?');
        if (cut >= 0) url = url.substring(0, cut);

        url = URL_TRAIL_SLASH.matcher(url).replaceAll("");

        int slash = url.indexOf('/');
        if (slash < 0) return url;                       // только домен

        String domain = url.substring(0, slash);         // домен + :порт
        String[] parts = url.substring(slash + 1).split("/");

        if (parts.length == 0) return domain;
        if (parts.length == 1) return domain + '/' + parts[0];

        return domain + '/' + parts[0] + '/' + parts[1];
    }

    /* ──────────────── phone ─────────────────────────────────────── */

    /**
     * * &lt; 10 цифр → «не телефон»
     * * `parse` + `isValidNumber` → форматируем в E.164
     * * при ошибке **ничего не меняем** – строка остаётся обычным текстом
     */
    private String tryNormalizePhone(String v) {

        // 1) удаляем префикс tel:
        String raw = v.startsWith("tel:") ? v.substring(4) : v;

        // 2) первичная фильтрация по длине
        String digits = PHONE_DIGITS.matcher(raw).replaceAll("");
        if (digits.length() < 10) return null;

        // 3) libphonenumber
        try {
            var num = phoneUtil.parse(raw, "");          // auto-region; требует + или 00
            if (phoneUtil.isValidNumber(num)) {
                return phoneUtil.format(num, PhoneNumberUtil.PhoneNumberFormat.E164);
            }
        } catch (NumberParseException ignored) {
            // fall through – будем трактовать как обычный текст
        }
        return null;
    }

    /* ──────────────── date ──────────────────────────────────────── */

    private String tryNormalizeDate(String v) {
        for (DateTimeFormatter fmt : DATE_FMT) {
            try {                               // дата-время
                LocalDateTime dt = LocalDateTime.parse(v, fmt);
                return dt.format(ISO_DATETIME);
            } catch (DateTimeParseException ignored1) {
                try {                           // только дата
                    LocalDate d = LocalDate.parse(v, fmt);
                    return d.format(ISO_DATE);
                } catch (DateTimeParseException ignored2) {
                    // try next pattern
                }
            }
        }
        return null;
    }

    /* ──────────────── misc ─────────────────────────────────────── */

    private boolean isFraction(String v) {
        return FRACTION_ONE_HALF.matcher(v).matches();
    }
}
