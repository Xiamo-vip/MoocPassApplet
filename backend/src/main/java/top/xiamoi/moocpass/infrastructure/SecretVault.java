package top.xiamoi.moocpass.infrastructure;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.config.MoocProperties;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class SecretVault {
    private final Db db;
    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    public SecretVault(Db db, MoocProperties properties) {
        this.db = db;
        if (!properties.getEncryptionKey().isBlank()) {
            for (String entry : properties.getEncryptionKey().split(",")) {
                String[] pair = entry.trim().split(":", 2);
                String version = pair.length == 2 ? pair[0] : "v1";
                byte[] key = Base64.getDecoder().decode(pair[pair.length - 1]);
                if (!version.matches("[a-zA-Z0-9_-]{1,32}") || key.length != 32)
                    throw new IllegalStateException("MOOC_ENCRYPTION_KEY must contain 256-bit Base64 keys");
                keys.put(version, new SecretKeySpec(key, "AES"));
            }
        }
    }
    public long store(long userId, String purpose, String value) {
        if (value == null || value.isBlank() || value.length() > 32000) throw ApiException.invalid("凭证为空或过长");
        if (keys.isEmpty()) throw unavailable();
        String version = keys.keySet().iterator().next();
        String mask = value.length() > 4 ? "****" + value.substring(value.length() - 4) : "****";
        try {
            byte[] iv = Crypto.bytes(12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(version), new GCMParameterSpec(128, iv));
            cipher.updateAAD((userId + ":" + purpose).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String ciphertext = Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
            return db.insert("INSERT INTO mp_secret_version(user_id,purpose,key_version,ciphertext,mask) VALUES(?,?,?,?,?)",
                userId, purpose, version, ciphertext, mask);
        } catch (java.security.GeneralSecurityException error) { throw unavailable(); }
    }
    public String reveal(long userId, long id) {
        var row = db.one("SELECT * FROM mp_secret_version WHERE id=? AND user_id=? AND revoked=FALSE", id, userId)
            .orElseThrow(() -> new ApiException("SECRET_REVOKED", "凭证已撤销，请更新配置", HttpStatus.CONFLICT));
        SecretKeySpec key = keys.get(Db.text(row, "keyVersion"));
        if (key == null) throw unavailable();
        try {
            byte[] bytes = Base64.getDecoder().decode(Db.text(row, "ciphertext"));
            if (bytes.length < 29) throw unavailable();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
            cipher.updateAAD((userId + ":" + Db.text(row, "purpose")).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException error) { throw unavailable(); }
    }
    public void revoke(long userId, long id) {
        db.update("UPDATE mp_secret_version SET revoked=TRUE WHERE id=? AND user_id=?", id, userId);
    }
    private ApiException unavailable() {
        return new ApiException("ENCRYPTION_UNAVAILABLE", "凭证加密配置不可用，请联系管理员", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
