package top.xiamoi.moocpass.account;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.platform.PlatformCatalog;
import top.xiamoi.moocpass.entity.UserPlatformConfig;
import java.util.*;

@Service
public class AccountService {
    private final Db db;
    private final SecretVault vault;
    private final PlatformCatalog catalog;
    public AccountService(Db db, SecretVault vault, PlatformCatalog catalog) {
        this.db = db; this.vault = vault; this.catalog = catalog;
    }
    public record Input(String platformCode, String username, String password, String token) {}
    public List<Map<String, Object>> list(long userId) {
        return db.list("SELECT id,platform_code,site_type,username,status,last_synced_at,created_at "
            + "FROM mp_platform_account WHERE user_id=? ORDER BY id", userId);
    }
    public Map<String, Object> owned(long userId, long id) {
        return db.one("SELECT * FROM mp_platform_account WHERE id=? AND user_id=?", id, userId).orElseThrow(ApiException::missing);
    }
    public Map<String, Object> bind(long userId, Long id, Input input) {
        if (input == null || input.platformCode() == null) throw ApiException.invalid("请选择平台");
        var adapter = catalog.require(input.platformCode());
        if (id != null) {
            var old = owned(userId, id);
            if (!Db.text(old, "platformCode").equals(input.platformCode())) throw ApiException.invalid("账号平台不能修改");
            if ("chaoxing".equals(input.platformCode())
                && !Db.text(old, "username").equals(Objects.toString(input.username(), "").trim()))
                throw ApiException.conflict("只能重新认证首次绑定的账号，不能更换账号");
        } else if (db.count("SELECT COUNT(*) FROM mp_platform_account WHERE user_id=? AND platform_code=?",
            userId, input.platformCode()) > 0) {
            throw ApiException.conflict("该平台已绑定账号，请使用重新认证");
        }
        String username = Objects.toString(input.username(), "").trim();
        String secret;
        boolean valid;
        String identity;
        if ("chaoxing".equals(input.platformCode())) {
            if (username.isBlank() || username.length() > 128 || input.password() == null || input.password().length() > 512)
                throw ApiException.invalid("请填写有效的账号与密码");
            secret = input.password();
            valid = adapter.validateAccount(username, secret);
            identity = username;
        } else {
            secret = Objects.toString(input.token(), "").trim();
            if (secret.isBlank() || secret.length() > 16000) throw ApiException.invalid("请填写有效的平台 Token");
            username = username.isBlank() ? "职教云账号" : username;
            if (username.length() > 128) throw ApiException.invalid("账号备注不能超过128个字符");
            identity = adapter.accountIdentity(username, secret);
            valid = identity != null;
        }
        if (!valid) throw new ApiException("PLATFORM_AUTH_EXPIRED", "平台认证未通过，请检查凭证或完成平台验证", HttpStatus.BAD_REQUEST);
        String legacyIdentity = null;
        long legacySecretId = 0;
        if (id != null && !"chaoxing".equals(input.platformCode())) {
            var old = owned(userId, id);
            if (Db.text(old, "platformIdentity").isBlank()) {
                legacySecretId = Db.id(old, "secretId");
                if ("UNBOUND".equals(Db.text(old, "status")))
                    throw ApiException.conflict("原账号身份未记录且已停用，无法安全确认是否同一账号");
                legacyIdentity = adapter.accountIdentity(username, vault.reveal(userId, legacySecretId));
                if (legacyIdentity == null)
                    throw ApiException.conflict("原账号身份无法验证，不能更换绑定账号");
            }
        }
        String label = username;
        String verifiedIdentity = identity;
        String verifiedLegacyIdentity = legacyIdentity;
        long verifiedLegacySecretId = legacySecretId;
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", userId).orElseThrow(ApiException::missing);
            if (id == null && db.count("SELECT COUNT(*) FROM mp_platform_account WHERE user_id=? AND platform_code=?",
                userId, input.platformCode()) > 0) throw ApiException.conflict("已绑定该平台，请使用重新认证");
            if (id != null) {
                var bound = owned(userId, id);
                if (!Db.text(bound, "platformCode").equals(input.platformCode())
                    || "chaoxing".equals(input.platformCode()) && !Db.text(bound, "username").equals(label))
                    throw ApiException.conflict("只能重新认证首次绑定的账号，不能更换账号");
                String originalIdentity = Db.text(bound, "platformIdentity");
                if (originalIdentity.isBlank() && "chaoxing".equals(input.platformCode()))
                    originalIdentity = Db.text(bound, "username");
                if (originalIdentity.isBlank() && verifiedLegacyIdentity != null
                    && verifiedLegacySecretId == Db.id(bound, "secretId"))
                    originalIdentity = verifiedLegacyIdentity;
                if (!originalIdentity.equals(verifiedIdentity))
                    throw ApiException.conflict("只能重新认证首次绑定的账号，不能更换账号");
            }
            long secretId = vault.store(userId, "PLATFORM", secret);
            if (id == null) {
                long created = db.insert("INSERT INTO mp_platform_account(user_id,platform_code,platform_identity,username,secret_id,status) VALUES(?,?,?,?,?,'ACTIVE')",
                    userId, input.platformCode(), verifiedIdentity, label, secretId);
                return Map.of("id", created, "status", "ACTIVE");
            }
            var old = owned(userId, id);
            db.update("UPDATE mp_platform_account SET platform_identity=?,secret_id=?,status='ACTIVE' WHERE id=? AND user_id=?",
                verifiedIdentity, secretId, id, userId);
            vault.revoke(userId, Db.id(old, "secretId"));
            return Map.of("id", id, "status", "ACTIVE");
        });
    }
    public UserPlatformConfig credential(long userId, long id) {
        var row = owned(userId, id);
        if ("UNBOUND".equals(Db.text(row, "status"))) throw ApiException.conflict("账号已停用，请重新认证");
        var config = UserPlatformConfig.builder().id(id).userId(userId)
            .platformCode(Db.text(row, "platformCode")).username(Db.text(row, "username")).status(1).build();
        String secret = vault.reveal(userId, Db.id(row, "secretId"));
        if ("chaoxing".equals(config.getPlatformCode())) config.setPassword(secret);
        else config.setToken(secret);
        return config;
    }
    public Map<String, Object> verify(long userId, long id) {
        var config = credential(userId, id);
        var adapter = catalog.require(config.getPlatformCode());
        boolean valid = "chaoxing".equals(config.getPlatformCode())
            ? adapter.validateAccount(config.getUsername(), config.getPassword()) : adapter.validateAuth(config.getToken());
        String state = valid ? "ACTIVE" : "EXPIRED";
        db.update("UPDATE mp_platform_account SET status=? WHERE id=? AND user_id=?", state, id, userId);
        return Map.of("status", state);
    }
    public void delete(long userId, long id) {
        db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", userId).orElseThrow(ApiException::missing);
            var row = owned(userId, id);
            if (db.count("SELECT COUNT(*) FROM mp_task WHERE account_id=? AND user_id=? AND state NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELED')",
                id, userId) > 0) throw ApiException.conflict("账号仍有关联任务，请先取消任务后停用");
            vault.revoke(userId, Db.id(row, "secretId"));
            db.update("UPDATE mp_platform_account SET status='UNBOUND' WHERE id=? AND user_id=?", id, userId);
            db.update("UPDATE mp_course SET resource_snapshot=NULL WHERE account_id=? AND user_id=?", id, userId);
            return null;
        });
    }
}
