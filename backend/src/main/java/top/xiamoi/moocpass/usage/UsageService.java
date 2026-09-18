package top.xiamoi.moocpass.usage;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import top.xiamoi.moocpass.answer.ProfileService;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.config.MoocProperties;
import top.xiamoi.moocpass.infrastructure.Db;
import java.time.*;
import java.util.*;

@Service
public class UsageService {
    private final Db db;
    private final ZoneId zone;
    public UsageService(Db db, MoocProperties properties) { this.db = db; zone = ZoneId.of(properties.getZone()); }
    public long reserve(ProfileService.Resolved profile, Long taskId, int reservedTokens) {
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", profile.userId()).orElseThrow(ApiException::missing);
            LocalDateTime day = LocalDate.now(zone).atStartOfDay();
            var settings = profile.settings();
            long requests = db.count("SELECT COUNT(*) FROM mp_api_usage WHERE user_id=? AND profile_id=? AND created_at>=?",
                profile.userId(), profile.id(), day);
            if (requests >= settings.dailyRequests()) throw quota();
            if (taskId != null && db.count("SELECT COUNT(*) FROM mp_api_usage WHERE user_id=? AND task_id=?",
                profile.userId(), taskId) >= settings.taskRequests()) throw quota();
            long tokens = db.count("SELECT COALESCE(SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL "
                + "THEN reserved_tokens ELSE input_tokens+output_tokens END),0) FROM mp_api_usage "
                + "WHERE user_id=? AND profile_id=? AND created_at>=?", profile.userId(), profile.id(), day);
            if (settings.dailyTokens() > 0 && tokens + reservedTokens > settings.dailyTokens()) throw quota();
            if (db.count("SELECT COUNT(*) FROM mp_api_usage WHERE user_id=? AND profile_id=? AND created_at>?",
                profile.userId(), profile.id(), LocalDateTime.now(zone).minusSeconds(settings.intervalSeconds())) > 0)
                throw new ApiException("PROVIDER_INTERVAL", "请等待请求间隔后重试", HttpStatus.TOO_MANY_REQUESTS);
            return db.insert("INSERT INTO mp_api_usage(user_id,task_id,profile_id,model,reserved_tokens) VALUES(?,?,?,?,?)",
                profile.userId(), taskId, profile.id(), Objects.toString(settings.model(), ""), reservedTokens);
        });
    }
    public void settle(long id, String state, Integer input, Integer output, long elapsed, String error) {
        db.update("UPDATE mp_api_usage SET state=?,input_tokens=?,output_tokens=?,elapsed_ms=?,error_code=? WHERE id=? AND state='RESERVED'",
            state, input, output, elapsed, error, id);
    }
    public Map<String, Object> list(long userId, int days) {
        int range = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now(zone).minusDays(range);
        var totals = db.one("SELECT COUNT(*) AS requests,COALESCE(SUM(input_tokens),0) AS input_tokens,"
            + "COALESCE(SUM(output_tokens),0) AS output_tokens,"
            + "COALESCE(SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL THEN 1 ELSE 0 END),0) AS unknown_usage,"
            + "COALESCE(SUM(CASE WHEN state='SUCCESS' THEN 1 ELSE 0 END),0) AS succeeded,"
            + "COALESCE(AVG(CASE WHEN elapsed_ms>0 THEN elapsed_ms END),0) AS avg_elapsed "
            + "FROM mp_api_usage WHERE user_id=? AND created_at>=?", userId, since).orElse(Map.of());
        var records = db.list("SELECT id,task_id,profile_id,model,state,input_tokens,output_tokens,elapsed_ms,error_code,created_at "
            + "FROM mp_api_usage WHERE user_id=? AND created_at>=? ORDER BY id DESC LIMIT 100", userId, since);
        return Map.of("totals", totals, "records", records, "days", range);
    }
    private ApiException quota() {
        return new ApiException("QUOTA_EXCEEDED", "已达到配置的调用额度，请调整额度或等待下一周期", HttpStatus.TOO_MANY_REQUESTS);
    }
}
