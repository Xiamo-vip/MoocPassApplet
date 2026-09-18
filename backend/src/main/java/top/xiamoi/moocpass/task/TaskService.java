package top.xiamoi.moocpass.task;

import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.account.AccountService;
import top.xiamoi.moocpass.answer.ProfileService;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.course.CourseService;
import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.platform.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

@Service
public class TaskService {
    public static final Set<String> TERMINAL = Set.of("COMPLETED","PARTIAL","FAILED","CANCELED");
    private final Db db;
    private final AccountService accounts;
    private final CourseService courses;
    private final PlatformCatalog catalog;
    private final ProfileService profiles;
    public TaskService(Db db, AccountService accounts, CourseService courses, PlatformCatalog catalog, ProfileService profiles) {
        this.db = db; this.accounts = accounts; this.courses = courses; this.catalog = catalog; this.profiles = profiles;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchInput(List<Long> courseIds, TaskSettings settings) {}
    public Map<String, Object> preflight(long userId, BatchInput input) {
        validateBatch(input);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long id : input.courseIds()) {
            try {
                var course = courses.owned(userId, id);
                prepare(userId, id, input.settings());
                items.add(Map.of("courseId", id, "courseName", Db.text(course,"name"), "valid", true));
            } catch (ApiException error) {
                items.add(Map.of("courseId", id, "valid", false, "code", error.code(), "message", error.getMessage()));
            }
        }
        return Map.of("items", items);
    }
    public Map<String, Object> create(long userId, String key, BatchInput input) {
        validateBatch(input);
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}")) throw ApiException.invalid("需要有效的幂等键");
        String hash = Crypto.hash(Json.write(input));
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", userId).orElseThrow(ApiException::missing);
            var previous = db.one("SELECT request_hash,result_json FROM mp_task_batch WHERE user_id=? AND idempotency_key=?", userId, key);
            if (previous.isPresent()) {
                if (!hash.equals(Db.text(previous.get(), "requestHash"))) throw ApiException.conflict("幂等键已用于不同的配置");
                return Json.map(Db.text(previous.get(), "resultJson"));
            }
            long batchId = db.insert("INSERT INTO mp_task_batch(user_id,idempotency_key,request_hash) VALUES(?,?,?)", userId, key, hash);
            List<Map<String, Object>> results = new ArrayList<>();
            for (Long id : input.courseIds()) {
                try {
                    TaskSettings settings = prepare(userId, id, input.settings());
                    var course = courses.owned(userId, id);
                    var active = db.one("SELECT task_id FROM mp_active_course WHERE account_id=? AND course_id=?", Db.id(course,"accountId"), id);
                    if (active.isPresent()) {
                        results.add(Map.of("courseId", id, "taskId", Db.id(active.get(),"taskId"), "existing", true, "valid", true));
                        continue;
                    }
                    long taskId = insertCourse(userId, course, batchId, null, settings);
                    results.add(Map.of("courseId", id, "taskId", taskId, "existing", false, "valid", true));
                } catch (ApiException error) {
                    results.add(Map.of("courseId", id, "valid", false, "code", error.code(), "message", error.getMessage()));
                }
            }
            Map<String, Object> result = Map.of("batchId", batchId, "items", results);
            db.update("UPDATE mp_task_batch SET result_json=? WHERE id=?", Json.write(result), batchId);
            return result;
        });
    }
    private long insertCourse(long userId, Map<String,Object> course, Long batchId, Long sourceId, TaskSettings settings) {
        long id = db.insert("INSERT INTO mp_task(user_id,account_id,course_id,batch_id,source_task_id,course_name,config_json) VALUES(?,?,?,?,?,?,?)",
            userId, Db.id(course,"accountId"), Db.id(course,"id"), batchId, sourceId, Db.text(course,"name"), Json.write(settings));
        db.update("INSERT INTO mp_active_course(account_id,course_id,task_id) VALUES(?,?,?)", Db.id(course,"accountId"), Db.id(course,"id"), id);
        return id;
    }
    private TaskSettings prepare(long userId, long courseId, TaskSettings settings) {
        settings.validate();
        var course = courses.owned(userId, courseId);
        var account = accounts.owned(userId, Db.id(course,"accountId"));
        if (!"ACTIVE".equals(Db.text(account,"status"))) throw ApiException.conflict("账号需要重新认证");
        catalog.require(Db.text(course,"platformCode"));
        if ("zhy".equals(Db.text(course,"platformCode")) && settings.autoAnswer())
            throw ApiException.invalid("职教云本期支持资源学习，暂未开放答题");
        Long primary = null, fallback = null;
        if (settings.autoAnswer()) {
            var primaryProfile = profiles.resolve(userId, settings.answerProfileId(), null);
            primary = primaryProfile.versionId();
            ProfileService.Resolved fallbackProfile = null;
            if (settings.fallbackProfileId() != null) {
                fallbackProfile = profiles.resolve(userId, settings.fallbackProfileId(), null);
                fallback = fallbackProfile.versionId();
            }
            if (settings.aiBestEffort() && !"AI".equals(primaryProfile.kind())
                && (fallbackProfile == null || !"AI".equals(fallbackProfile.kind())))
                throw ApiException.invalid("AI 尽力作答需要选择 AI 模型配置");
        }
        return settings.versions(primary, fallback);
    }
    private void validateBatch(BatchInput input) {
        if (input == null || input.settings() == null || input.courseIds() == null || input.courseIds().isEmpty()
            || input.courseIds().size() > 30 || input.courseIds().stream().anyMatch(id -> id == null || id <= 0)
            || new HashSet<>(input.courseIds()).size() != input.courseIds().size())
            throw ApiException.invalid("请选择 1–30 门不重复的课程");
        input.settings().validate();
    }
    public Map<String,Object> sync(long userId, long accountId) {
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE", userId).orElseThrow(ApiException::missing);
            var account = accounts.owned(userId,accountId);
            catalog.require(Db.text(account,"platformCode"));
            var active = db.one("SELECT id FROM mp_task WHERE user_id=? AND account_id=? AND kind='SYNC' "
                + "AND state NOT IN ('COMPLETED','PARTIAL','FAILED','CANCELED') ORDER BY id DESC LIMIT 1", userId,accountId);
            if (active.isPresent()) return Map.of("id", Db.id(active.get(),"id"));
            long id = db.insert("INSERT INTO mp_task(user_id,account_id,kind,course_name,config_json) VALUES(?,?,'SYNC','同步课程',?)",
                userId,accountId,Json.write(TaskSettings.defaults()));
            return Map.of("id",id);
        });
    }
    public Map<String,Object> owned(long userId, long id) {
        return db.one("SELECT * FROM mp_task WHERE id=? AND user_id=?",id,userId).orElseThrow(ApiException::missing);
    }
    public Map<String,Object> detail(long userId, long id) {
        var row = owned(userId,id);
        row.put("settings", Json.read(Db.text(row,"configJson")));
        for (String field : List.of("configJson","owner","leaseUntil","logSeq","userId")) row.remove(field);
        return row;
    }
    public Map<String,Object> list(long userId, String state, int page) {
        int offset = Math.max(0,Math.min(page-1,10000))*30;
        String filter = state == null || state.isBlank() ? "" : state;
        var items = db.list("SELECT id,account_id,course_id,kind,course_name,state,progress,platform_progress,current_step,"
            + "error_code,error_message,created_at,updated_at FROM mp_task WHERE user_id=? AND (?='' OR state=?) ORDER BY id DESC LIMIT 30 OFFSET ?",
            userId,filter,filter,offset);
        long total = db.count("SELECT COUNT(*) FROM mp_task WHERE user_id=? AND (?='' OR state=?)",userId,filter,filter);
        var summary = db.list("SELECT state,COUNT(*) AS total FROM mp_task WHERE user_id=? GROUP BY state",userId);
        return Map.of("items",items,"total",total,"summary",summary);
    }
    public Map<String,Object> action(long userId,long id,String action) {
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_user WHERE id=? FOR UPDATE",userId).orElseThrow(ApiException::missing);
            var task = db.one("SELECT * FROM mp_task WHERE id=? AND user_id=? FOR UPDATE",id,userId).orElseThrow(ApiException::missing);
            String state = Db.text(task,"state");
            String next;
            switch(action) {
                case "pause" -> {
                    if (Set.of("PAUSED","PAUSING").contains(state)) return Map.of("id",id,"state",state);
                    if (TERMINAL.contains(state) || "CANCELING".equals(state)) throw ApiException.conflict("当前任务不能暂停");
                    next = task.get("owner") != null ? "PAUSING" : "PAUSED";
                }
                case "cancel" -> {
                    if (TERMINAL.contains(state)) return Map.of("id",id,"state",state);
                    next = task.get("owner") != null ? "CANCELING" : "CANCELED";
                }
                case "resume" -> {
                    if (!Set.of("PAUSED","WAITING_AUTH","WAITING_USER","WAITING_QUOTA","RETRY_WAIT").contains(state))
                        throw ApiException.conflict("当前任务不能恢复");
                    if (!"ACTIVE".equals(Db.text(accounts.owned(userId,Db.id(task,"accountId")),"status")))
                        throw ApiException.conflict("请先重新认证平台账号");
                    next = "QUEUED";
                }
                case "retry" -> {
                    if (!TERMINAL.contains(state) || !"COURSE".equals(Db.text(task,"kind"))) throw ApiException.conflict("该任务不能重试");
                    var course = courses.owned(userId,Db.id(task,"courseId"));
                    if (db.count("SELECT COUNT(*) FROM mp_active_course WHERE account_id=? AND course_id=?",Db.id(task,"accountId"),Db.id(task,"courseId"))>0)
                        throw ApiException.conflict("该课程已有未结束任务");
                    var settings = prepare(userId,Db.id(task,"courseId"),Json.read(Db.text(task,"configJson"),TaskSettings.class));
                    return Map.of("id",insertCourse(userId,course,null,id,settings),"state","QUEUED");
                }
                default -> throw ApiException.invalid("不支持的任务操作");
            }
            db.update("UPDATE mp_task SET state=?,error_code='',error_message='',next_run_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                next,id);
            if (TERMINAL.contains(next)) db.update("DELETE FROM mp_active_course WHERE task_id=?",id);
            return Map.of("id",id,"state",next);
        });
    }
    public List<Map<String,Object>> logs(long userId,long id,long after) {
        owned(userId,id);
        return db.list("SELECT seq,level,event_type,message,created_at FROM mp_task_log WHERE task_id=? AND seq>? ORDER BY seq LIMIT 100",id,Math.max(0,after));
    }
}
