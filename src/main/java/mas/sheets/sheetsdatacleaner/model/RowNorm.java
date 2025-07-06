package mas.sheets.sheetsdatacleaner.model;

public record RowNorm(int idx, String value, long rowId) {

    /**
     * Создаёт RowNorm, вычисляя стабильный 64-битный идентификатор строки
     * из нормализованного значения {@code value}. Используется SHA-256 и
     * берутся первые 8 байт хэша (big-endian).
     */
    public static RowNorm of(int idx, String value) {
        return new RowNorm(idx, value, hash64(value));
    }

    private static long hash64(String v) {
        if (v == null) return 0L;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // первые 8 байт -> long
            return java.nio.ByteBuffer.wrap(digest).getLong();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}