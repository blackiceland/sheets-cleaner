package mas.sheets.sheetsdatacleaner.service.type_detector;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import mas.sheets.sheetsdatacleaner.enums.DataType;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class RegexDataTypeDetector implements DataTypeDetector {

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("^(https?://)?[\\w.-]+\\.[a-z]{2,}.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC = Pattern.compile("^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISBN = Pattern.compile("^(97(8|9))?\\d{9}[\\dX]$");
    private static final Pattern HASH32 = Pattern.compile("^[0-9a-f]{32}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HASH40 = Pattern.compile("^[0-9a-f]{40}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEO = Pattern.compile("^[+-]?\\d{1,3}\\.\\d+[,\\s]+[+-]?\\d{1,3}\\.\\d+$");

    private final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    @Override
    public DataType detect(String raw) {
        if (raw == null || raw.isBlank()) return DataType.GENERAL;
        String s = raw.strip();

        if (EMAIL.matcher(s).matches()) return DataType.EMAIL;
        if (URL.matcher(s).matches()) return DataType.URL;
        if (MAC.matcher(s).matches()) return DataType.MAC;
        if (ISBN.matcher(s.replaceAll("[^0-9X]", "")).matches()) return DataType.ISBN;
        if (HASH32.matcher(s).matches()
                || HASH40.matcher(s).matches()) return DataType.HEX_HASH;
        if (GEO.matcher(s).matches()) return DataType.GEO;

        try {
            Phonenumber.PhoneNumber n = phoneUtil.parse(s, "");
            if (phoneUtil.isValidNumber(n)) return DataType.PHONE;
        } catch (NumberParseException ignored) {
        }

        return DataType.GENERAL;
    }
}

