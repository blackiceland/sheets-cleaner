package mas.sheets.sheetsdatacleaner.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ApiPaths {

    public static final String API_V1 = "/api/v1";
    public static final String SHEETS = API_V1 + "/sheets";
    public static final String DUPLICATES = SHEETS + "/duplicates";

    public static final String DUPLICATES_EXACT = DUPLICATES + "/exact";
    public static final String DUPLICATES_FUZZY = DUPLICATES + "/fuzzy";

}