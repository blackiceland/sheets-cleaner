package mas.sheets.sheetsdatacleaner.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import mas.sheets.sheetsdatacleaner.dto.DatasetPayload;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Утилита кодирования datasetToken.<br/>
 * Формат: [data | hmac], где data =
 * <pre>
 *  version (1B)
 *  ivLen   (1B)
 *  iv      (ivLen B)
 *  cipherLen (4B, BE)
 *  cipher (cipherLen B) // AES-GCM, включает тег
 * </pre>
 * HMAC = HmacSHA256(data, hmacKey).
 */
public final class DatasetTokenUtil {

    private static final byte VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_URL_DEC = Base64.getUrlDecoder();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int MAX_TOKEN_BYTES = 2 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 5 * 1024 * 1024;

    private DatasetTokenUtil() {}

    public static String encode(DatasetPayload payload, byte[] secret) {
        if (secret == null || secret.length < 32)
            throw new IllegalArgumentException("Secret key must be >=32 bytes (16 AES + 16 HMAC)");

        byte[] aesKeyBytes = new byte[16];
        byte[] hmacKeyBytes = new byte[16];
        System.arraycopy(secret, 0, aesKeyBytes, 0, 16);
        System.arraycopy(secret, 16, hmacKeyBytes, 0, 16);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        byte[] json;
        try {
            json = JSON.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize payload", e);
        }

        byte[] compressed = gzip(json);

        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);

        byte[] cipher;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher = c.doFinal(compressed);
        } catch (Exception ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }

        ByteBuffer bb = ByteBuffer.allocate(1 + 1 + IV_LEN + 4 + cipher.length);
        bb.put(VERSION);
        bb.put((byte) IV_LEN);
        bb.put(iv);
        bb.putInt(cipher.length);
        bb.put(cipher);
        byte[] data = bb.array();

        byte[] hmac = hmacSha256(data, hmacKeyBytes);

        byte[] out = new byte[data.length + hmac.length];
        System.arraycopy(data, 0, out, 0, data.length);
        System.arraycopy(hmac, 0, out, data.length, hmac.length);

        return B64_URL.encodeToString(out);
    }

    public static DatasetPayload decode(String token, byte[] secret) {
        if (secret == null || secret.length < 32)
            throw new IllegalArgumentException("Secret key must be >=32 bytes");

        if (token.length() > MAX_TOKEN_BYTES * 4 / 3) // base64 expands ~33%
            throw new IllegalArgumentException("Token too large");

        byte[] all;
        try {
            all = B64_URL_DEC.decode(token);
        } catch (IllegalArgumentException iae) {
            throw new IllegalArgumentException("Invalid Base64 token", iae);
        }

        if (all.length < 1 + 1 + IV_LEN + 4 + 16 + 32)
            throw new IllegalArgumentException("Token too small");

        int hmacOff = all.length - 32;
        byte[] data = new byte[hmacOff];
        byte[] sig = new byte[32];
        System.arraycopy(all, 0, data, 0, hmacOff);
        System.arraycopy(all, hmacOff, sig, 0, 32);

        byte[] hmacKeyBytes = new byte[16];
        System.arraycopy(secret, 16, hmacKeyBytes, 0, 16);
        byte[] expected = hmacSha256(data, hmacKeyBytes);

        if (!MessageDigest.isEqual(expected, sig))
            throw new IllegalArgumentException("Invalid token signature");

        ByteBuffer bb = ByteBuffer.wrap(data);
        byte ver = bb.get();
        if (ver != VERSION)
            throw new IllegalArgumentException("Unsupported token version: " + ver);

        int ivLen = bb.get() & 0xFF;
        if (ivLen != IV_LEN)
            throw new IllegalArgumentException("Unexpected IV length");
        byte[] iv = new byte[ivLen];
        bb.get(iv);
        int cLen = bb.getInt();
        if (cLen < 16 || cLen > bb.remaining())
            throw new IllegalArgumentException("Invalid cipher length");
        byte[] cipher = new byte[cLen];
        bb.get(cipher);

        byte[] aesKeyBytes = new byte[16];
        System.arraycopy(secret, 0, aesKeyBytes, 0, 16);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        byte[] compressed;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            compressed = c.doFinal(cipher);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Decryption failed", ex);
        }

        byte[] json = gunzip(compressed);

        if (json.length > MAX_JSON_BYTES)
            throw new IllegalArgumentException("Decompressed payload too large");

        DatasetPayload payload;
        try {
            payload = JSON.readValue(json, DatasetPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse payload", e);
        }

        long now = Instant.now().getEpochSecond();
        if (payload.exp() < now)
            throw new IllegalArgumentException("Token expired");

        return payload;
    }

    private static byte[] gzip(byte[] src) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(src);
            gz.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("GZIP failed", e);
        }
    }

    private static byte[] gunzip(byte[] src) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(src));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            gz.transferTo(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Gunzip failed", e);
        }
    }

    private static byte[] hmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec ks = new SecretKeySpec(key, "HmacSHA256");
            mac.init(ks);
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
} 