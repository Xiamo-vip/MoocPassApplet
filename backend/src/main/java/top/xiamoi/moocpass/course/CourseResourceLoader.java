package top.xiamoi.moocpass.course;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.task.TaskSignal;
import java.util.*;
import java.util.concurrent.*;

@Service
public class CourseResourceLoader {
    private final CourseService courses;
    private final Map<Key, Load> loads = new HashMap<>();
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "course-resource-loader");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    public CourseResourceLoader(CourseService courses) { this.courses = courses; }

    public Map<String, Object> get(long userId, long courseId, boolean refresh) {
        Map<String, Object> snapshot = courses.resourceSnapshot(userId, courseId);
        Key key = new Key(userId, courseId);
        Load load;
        synchronized (loads) {
            long now = System.currentTimeMillis();
            loads.entrySet().removeIf(entry -> entry.getValue().finishedAt > 0
                && now - entry.getValue().finishedAt > TimeUnit.MINUTES.toMillis(10));
            load = loads.get(key);
            if (load == null || load.finishedAt > 0 && (refresh || now - load.finishedAt > 60_000)) {
                Load next = new Load();
                try {
                    executor.execute(() -> run(key, next));
                } catch (RejectedExecutionException e) {
                    throw ApiException.conflict("章节加载繁忙，请稍后重试");
                }
                loads.put(key, next);
                load = next;
            }
        }
        Map<String, Object> status = load.status;
        if ("SYNCED".equals(status.get("status"))) snapshot = courses.resourceSnapshot(userId, courseId);
        Map<String, Object> result = new LinkedHashMap<>(snapshot);
        result.putAll(status);
        return result;
    }

    private void run(Key key, Load load) {
        try {
            load.status = Map.of("status", "LOADING", "progress", 0, "message", "正在连接学习平台");
            courses.refreshResources(key.userId, key.courseId, (completed, total) -> {
                int progress = total > 0 ? Math.min(99, completed * 100 / total) : 0;
                load.status = Map.of("status", "LOADING", "progress", progress,
                    "message", "已读取 " + completed + " / " + total + " 个章节");
            });
            load.status = Map.of("status", "SYNCED", "progress", 100, "message", "同步完成");
        } catch (Exception e) {
            String message = e instanceof ApiException || e instanceof TaskSignal
                ? e.getMessage() : "章节同步失败，请稍后重试";
            load.status = Map.of("status", "FAILED", "progress", 0, "message", message);
        } finally {
            load.finishedAt = System.currentTimeMillis();
        }
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }

    private record Key(long userId, long courseId) {}
    private static class Load {
        volatile long finishedAt;
        volatile Map<String, Object> status = Map.of("status", "LOADING", "progress", 0, "message", "等待加载章节");
    }
}
