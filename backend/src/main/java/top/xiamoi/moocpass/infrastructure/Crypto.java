package top.xiamoi.moocpass.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Crypto() {}
    public static byte[] bytes(int size) {
        byte[] value = new byte[size];
        RANDOM.nextBytes(value);
        return value;
    }
    public static String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32)); }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
