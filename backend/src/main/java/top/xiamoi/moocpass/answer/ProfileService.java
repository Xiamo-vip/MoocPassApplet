package top.xiamoi.moocpass.answer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.infrastructure.*;
import java.util.*;

@Service
public class ProfileService {
    private final Db db;
    private final SecretVault vault;
    private final OutboundHttp outbound;
    public ProfileService(Db db, SecretVault vault, OutboundHttp outbound) {
        this.db = db; this.vault = vault; this.outbound = outbound;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Input(String name, String kind, String apiKey, @JsonAlias("clearSecret") boolean clearKey,
                        boolean isDefault, ProfileSettings settings) {}
    public record Resolved(long id, long versionId, long userId, String kind, Long secretId, ProfileSettings settings) {}

    public List<Map<String, Object>> list(long userId) {
        var rows = db.list("SELECT p.id,p.name,p.kind,p.is_default,p.current_version_id,v.config_json,s.mask,"
            + "s.revoked FROM mp_answer_profile p JOIN mp_profile_version v ON v.id=p.current_version_id "
            + "LEFT JOIN mp_secret_version s ON s.id=v.secret_id WHERE p.user_id=? AND p.deleted=FALSE ORDER BY p.id DESC", userId);
        for (var row : rows) {
            row.put("settings", Json.read(Db.text(row, "configJson")));
            row.remove("configJson");
            row.put("hasKey", row.get("mask") != null && !Db.flag(row, "revoked"));
            row.remove("revoked");
        }
        return rows;
    }
    public Map<String, Object> save(long userId, Long profileId, Input input) {
        final Input normalizedInput = normalize(input);
        validate(normalizedInput);
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", userId).orElseThrow(ApiException::missing);
            Long secretId = null;
            long id;
            if (profileId == null) {
                id = db.insert("INSERT INTO mp_answer_profile(user_id,name,kind,is_default) VALUES(?,?,?,FALSE)",
                    userId, normalizedInput.name().trim(), normalizedInput.kind());
            } else {
                var old = owned(userId, profileId);
                id = profileId;
                if (!Db.text(old, "kind").equals(normalizedInput.kind())) throw ApiException.invalid("配置协议类型不可修改，请新建配置");
                if (old.get("currentVersionId") != null) {
                    var previous = db.one("SELECT secret_id FROM mp_profile_version WHERE id=?",
                        Db.id(old, "currentVersionId")).orElseThrow(ApiException::missing);
                    if (previous.get("secretId") != null) secretId = Db.id(previous, "secretId");
                }
            }
            if (normalizedInput.clearKey()) {
                ensureNotInUse(userId, id);
                if (secretId != null) vault.revoke(userId, secretId);
                secretId = null;
            }
            if (normalizedInput.apiKey() != null && !normalizedInput.apiKey().isBlank()) {
                if (normalizedInput.apiKey().contains("****")) throw ApiException.invalid("请填写真实密钥，不能保存掩码");
                secretId = vault.store(userId, "ANSWER_API", normalizedInput.apiKey().trim());
            }
            long version = db.insert("INSERT INTO mp_profile_version(profile_id,user_id,secret_id,config_json) VALUES(?,?,?,?)",
                id, userId, secretId, Json.write(normalizedInput.settings()));
            boolean makeDefault = normalizedInput.isDefault() || db.count(
                "SELECT COUNT(*) FROM mp_answer_profile WHERE user_id=? AND is_default=TRUE AND deleted=FALSE", userId) == 0;
            if (makeDefault) db.update("UPDATE mp_answer_profile SET is_default=FALSE WHERE user_id=?", userId);
            db.update("UPDATE mp_answer_profile SET name=?,current_version_id=?,is_default=? WHERE id=? AND user_id=?",
                normalizedInput.name().trim(), version, makeDefault, id, userId);
            return Map.of("id", id, "versionId", version);
        });
    }
    private void validate(Input input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 80)
            throw ApiException.invalid("请输入配置名称");
        if (!Set.of("AI", "TIKU").contains(input.kind())) throw ApiException.invalid("不支持的接口类型");
        ProfileSettings settings = input.settings();
        if (settings == null || settings.baseUrl() == null || settings.path() == null)
            throw ApiException.invalid("请填写接口地址和请求路径");
        if (!settings.path().startsWith("/") || settings.path().contains("://") || settings.path().contains(".."))
            throw ApiException.invalid("请求路径格式无效");
        var base = outbound.validate(settings.baseUrl());
        if (base.query() != null) throw ApiException.invalid("基础地址不能包含查询参数");
        outbound.validate(endpoint(settings));
        if (settings.intervalSeconds() < 1 || settings.intervalSeconds() > 60
            || settings.dailyRequests() < 1 || settings.dailyRequests() > 10000
            || settings.taskRequests() < 1 || settings.taskRequests() > settings.dailyRequests()
            || settings.dailyTokens() < 0 || settings.reservedTokens() < 256 || settings.reservedTokens() > 32000)
            throw ApiException.invalid("请求间隔或额度超出允许范围");
        if ("AI".equals(input.kind()) && (settings.model() == null || settings.model().isBlank() || settings.model().length() > 128))
            throw ApiException.invalid("请填写模型 ID");
        if (!Set.of("GET", "POST").contains(settings.method())) throw ApiException.invalid("请求方法只支持 GET 或 POST");
        if (settings.authHeader() == null || !settings.authHeader().matches("[A-Za-z][A-Za-z0-9-]{0,63}")
            || Set.of("host","cookie","content-length","connection","transfer-encoding","proxy-authorization")
                .contains(settings.authHeader().toLowerCase(Locale.ROOT)))
            throw ApiException.invalid("认证请求头无效");
        if (settings.authPrefix() == null || settings.authPrefix().length() > 32
            || settings.authPrefix().contains("\r") || settings.authPrefix().contains("\n"))
            throw ApiException.invalid("认证前缀无效");
        for (String path : List.of(Objects.toString(settings.answerPath(), ""), Objects.toString(settings.successPath(), ""))) {
            if (!path.matches("[A-Za-z0-9_.-]{0,150}")) throw ApiException.invalid("字段路径只支持点分隔的键与数组下标");
        }
        if (settings.data() != null && (settings.data().size() > 20 || Json.write(settings.data()).length() > 8000))
            throw ApiException.invalid("题库请求模板过大");
    }
    public Resolved resolve(long userId, long id, Long versionId) {
        var profile = owned(userId, id);
        long version = versionId == null ? Db.id(profile, "currentVersionId") : versionId;
        var row = db.one("SELECT id,secret_id,config_json FROM mp_profile_version WHERE id=? AND profile_id=? AND user_id=?",
            version, id, userId).orElseThrow(ApiException::missing);
        Long secretId = row.get("secretId") == null ? null : Db.id(row, "secretId");
        if ("AI".equals(Db.text(profile, "kind")) && secretId == null) throw ApiException.invalid("该模型尚未配置密钥");
        if (secretId != null) vault.reveal(userId, secretId);
        ProfileSettings settings = Json.read(Db.text(row, "configJson"), ProfileSettings.class);
        settings = normalizeSettings(Db.text(profile, "kind"), settings);
        return new Resolved(id, version, userId, Db.text(profile, "kind"), secretId,
            settings);
    }
    private Input normalize(Input input) {
        if (input == null || input.settings() == null) return input;
        if (!"AI".equals(input.kind())) return input;
        ProfileSettings settings = normalizeSettings(input.kind(), input.settings());
        if (settings != input.settings()) return new Input(input.name(), input.kind(), input.apiKey(), input.clearKey(), input.isDefault(), settings);
        return input;
    }
    private ProfileSettings normalizeSettings(String kind, ProfileSettings settings) {
        if (!"AI".equals(kind) || settings == null) return settings;
        String path = Objects.toString(settings.path(), "").trim();
        String base = Objects.toString(settings.baseUrl(), "").trim().replaceAll("/+$", "");
        if (path.isBlank() || "/".equals(path)) {
            String defaultPath = base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
            return new ProfileSettings(settings.baseUrl(), defaultPath, settings.model(), settings.method(),
                settings.data(), settings.answerPath(), settings.successPath(), settings.successValue(),
                settings.authHeader(), settings.authPrefix(), settings.intervalSeconds(), settings.dailyRequests(),
                settings.taskRequests(), settings.dailyTokens(), settings.reservedTokens());
        }
        return settings;
    }
    public String secret(Resolved profile) {
        return profile.secretId() == null ? "" : vault.reveal(profile.userId(), profile.secretId());
    }
    public Map<String, Object> owned(long userId, long id) {
        return db.one("SELECT * FROM mp_answer_profile WHERE id=? AND user_id=? AND deleted=FALSE", id, userId)
            .orElseThrow(ApiException::missing);
    }
    public void delete(long userId, long id) {
        db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", userId).orElseThrow(ApiException::missing);
            owned(userId, id);
            ensureNotInUse(userId, id);
            db.update("UPDATE mp_answer_profile SET deleted=TRUE,is_default=FALSE WHERE id=? AND user_id=?", id, userId);
            for (var secret : db.list("SELECT DISTINCT secret_id FROM mp_profile_version WHERE profile_id=? AND secret_id IS NOT NULL", id))
                vault.revoke(userId, Db.id(secret, "secretId"));
            return null;
        });
    }
    private void ensureNotInUse(long userId, long profileId) {
        for (var task : db.list("SELECT config_json FROM mp_task WHERE user_id=? AND state NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELED')", userId)) {
            var settings = Json.read(Db.text(task, "configJson"));
            if (settings.path("answerProfileId").asLong(0) == profileId || settings.path("fallbackProfileId").asLong(0) == profileId)
                throw ApiException.conflict("配置正被未结束的任务使用，请先取消相关任务");
        }
    }
    public static String endpoint(ProfileSettings settings) {
        String base = Objects.toString(settings.baseUrl(), "").trim().replaceAll("/+$", "");
        String path = Objects.toString(settings.path(), "").trim();
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (path.isEmpty() || "/".equals(path)) {
            path = base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
        } else {
            if (base.endsWith("/v1") && path.startsWith("/v1/")) {
                path = path.substring(3);
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
        }
        return base + path;
    }
}
