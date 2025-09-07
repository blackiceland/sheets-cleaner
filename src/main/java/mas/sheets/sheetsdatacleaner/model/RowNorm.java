package mas.sheets.sheetsdatacleaner.model;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public record RowNorm(int idx, String value, long rowId) {

    public static RowNorm of(int idx, String value) {
        return new RowNorm(idx, value, hash64(value));
    }

    private static long hash64(String stringValue) {
        if (stringValue == null) {
            return 0L;
        }

        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(stringValue.getBytes(StandardCharsets.UTF_8));

            return ByteBuffer.wrap(digest).getLong();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}