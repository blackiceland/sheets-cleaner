package mas.sheets.sheetsdatacleaner.util;

import mas.sheets.sheetsdatacleaner.dto.DatasetPayload;
import mas.sheets.sheetsdatacleaner.model.RowNorm;
import mas.sheets.sheetsdatacleaner.model.ExactDetectionResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class DatasetTokenUtilTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    @Test
    void encodeDecodeRoundTrip() {
        RowNorm r = RowNorm.of(0, "test value");
        DatasetPayload p = new DatasetPayload(
                System.currentTimeMillis() / 1000 + 60,
                List.of(r),
                new ExactDetectionResult(List.of(), List.of(), List.of(), java.util.Map.of()),
                false);

        String token = DatasetTokenUtil.encode(p, SECRET);
        DatasetPayload decoded = DatasetTokenUtil.decode(token, SECRET);

        Assertions.assertEquals(p.rows().size(), decoded.rows().size());
        Assertions.assertEquals(p.rows().getFirst().rowId(), decoded.rows().getFirst().rowId());
    }

    @Test
    void expiredTokenThrows() {
        DatasetPayload p = new DatasetPayload(
                System.currentTimeMillis() / 1000 - 1,
                List.of(),
                new ExactDetectionResult(List.of(), List.of(), List.of(), java.util.Map.of()),
                false);

        String token = DatasetTokenUtil.encode(p, SECRET);
        Assertions.assertThrows(IllegalArgumentException.class, () -> DatasetTokenUtil.decode(token, SECRET));
    }
} 