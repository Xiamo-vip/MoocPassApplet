package top.xiamoi.moocpass.course;

import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.account.AccountService;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.platform.*;
import top.xiamoi.moocpass.vo.CourseVO;
import java.util.*;

@Service
public class CourseService {
    private final Db db;
    private final AccountService accounts;
    private final PlatformCatalog catalog;
    public CourseService(Db db, AccountService accounts, PlatformCatalog catalog) {
        this.db = db; this.accounts = accounts; this.catalog = catalog;
    }
    public Map<String, Object> list(long userId, Long accountId, String query, int page) {
        int limit = 100;
        int offset = Math.max(0, Math.min(page - 1, 10000)) * limit;
        String filter = " WHERE c.user_id=? AND a.status<>'UNBOUND' AND (? IS NULL OR c.account_id=?) AND c.platform_progress IS NOT NULL AND c.name LIKE ?";
        String search = "%" + Objects.toString(query, "").replace("%", "").replace("_", "") + "%";
        var rows = db.list("SELECT c.id,c.account_id,c.name,c.teacher,c.cover_url,c.platform_progress,c.synced_at,"
            + "a.platform_code,a.username FROM mp_course c JOIN mp_platform_account a ON a.id=c.account_id"
            + filter + " ORDER BY c.id DESC LIMIT " + limit + " OFFSET ?", userId, accountId, accountId, search, offset);
        long total = db.count("SELECT COUNT(*) FROM mp_course c JOIN mp_platform_account a ON a.id=c.account_id" + filter,
            userId, accountId, accountId, search);
        return Map.of("items", rows, "total", total, "page", Math.max(1, page));
    }
    public Map<String, Object> owned(long userId, long id) {
        return db.one("SELECT c.*,a.platform_code FROM mp_course c JOIN mp_platform_account a ON a.id=c.account_id "
            + "WHERE c.id=? AND c.user_id=? AND a.status<>'UNBOUND'", id, userId).orElseThrow(ApiException::missing);
    }
    public Map<String, Object> refreshResources(long userId, long id,
                                               java.util.function.BiConsumer<Integer, Integer> progress) {
        var course = owned(userId, id);
        var credential = accounts.credential(userId, Db.id(course, "accountId"));
        var adapter = catalog.require(Db.text(course, "platformCode"));
        var external = toExternal(course);
        var resList = adapter.getResources(credential, external, progress);
        updateResources(userId, id, resList, external.getPlatformProgress());
        return resourceSnapshot(userId, id);
    }
    public Map<String, Object> resourceSnapshot(long userId, long id) {
        var course = owned(userId, id);
        String snapshot = Db.text(course, "resourceSnapshot");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("course", publicCourse(course));
        result.put("resources", snapshot.isBlank() ? List.of() : Json.read(snapshot));
        result.put("status", snapshot.isBlank() ? "NEEDS_SYNC" : "SYNCED");
        return result;
    }
    public Map<String, Object> sync(long userId, long accountId) {
        var credential = accounts.credential(userId, accountId);
        var adapter = catalog.require(credential.getPlatformCode());
        var courseList = adapter.getCourseList(credential);
        int synced = 0;
        Set<String> activeExternalIds = new HashSet<>();
        for (CourseVO course : courseList) {
            top.xiamoi.moocpass.task.ExecutionScope.check();
            if (course.getCourseId() == null || course.getCourseId().isBlank()) continue;
            String classId = Objects.toString(course.getClassId(), "");
            activeExternalIds.add(course.getCourseId());
            db.update("INSERT INTO mp_course(user_id,account_id,external_id,class_id,name,teacher,cover_url,platform_progress) VALUES(?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE name=VALUES(name),teacher=VALUES(teacher),cover_url=VALUES(cover_url),platform_progress=VALUES(platform_progress),synced_at=CURRENT_TIMESTAMP",
                userId, accountId, course.getCourseId(), classId, Objects.toString(course.getName(), "未命名课程"),
                Objects.toString(course.getTeacher(), ""), Objects.toString(course.getCoverUrl(), ""), course.getPlatformProgress());
            synced++;
        }
        if (!activeExternalIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(activeExternalIds.size(), "?"));
            List<Object> params = new ArrayList<>();
            params.add(accountId);
            params.addAll(activeExternalIds);
            params.add(accountId);
            db.update("DELETE FROM mp_course WHERE account_id=? AND external_id NOT IN (" + placeholders + ") "
                + "AND id NOT IN (SELECT course_id FROM mp_task WHERE account_id=?)", params.toArray());
        }
        db.update("UPDATE mp_platform_account SET status='ACTIVE',last_synced_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", accountId, userId);
        return Map.of("synced", true, "count", synced, "message", "成功同步 " + synced + " 门有任务点的网课");
    }
    public void updateResources(long userId, long id, List<CourseResource> resources) {
        updateResources(userId, id, resources, null);
    }
    public void updateResources(long userId, long id, List<CourseResource> resources, Integer platformProgress) {
        db.update("UPDATE mp_course SET resource_snapshot=?,platform_progress=COALESCE(?,platform_progress),synced_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
            Json.write(resources), platformProgress, id, userId);
    }
    public CourseVO toExternal(Map<String, Object> row) {
        return CourseVO.builder().courseId(Db.text(row, "externalId")).classId(Db.text(row, "classId"))
            .name(Db.text(row, "name")).teacher(Db.text(row, "teacher")).coverUrl(Db.text(row, "coverUrl")).build();
    }
    private Map<String, Object> publicCourse(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("resourceSnapshot"); copy.remove("userId"); copy.remove("externalId"); copy.remove("classId");
        return copy;
    }
}
