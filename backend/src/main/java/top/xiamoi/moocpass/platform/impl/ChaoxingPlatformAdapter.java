package top.xiamoi.moocpass.platform.impl;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import top.xiamoi.moocpass.entity.CourseTask;
import top.xiamoi.moocpass.entity.UserPlatformConfig;
import top.xiamoi.moocpass.entity.UserQuestionConfig;
import top.xiamoi.moocpass.mapper.CourseTaskMapper;
import top.xiamoi.moocpass.platform.MoocPlatformAdapter;
import top.xiamoi.moocpass.solver.QuestionSolver;
import top.xiamoi.moocpass.task.TaskLogger;
import top.xiamoi.moocpass.util.OkHttpUtil;
import top.xiamoi.moocpass.vo.CourseVO;
import top.xiamoi.moocpass.answer.QuestionAnswerService;
import top.xiamoi.moocpass.platform.CourseResource;
import top.xiamoi.moocpass.task.ExecutionScope;
import top.xiamoi.moocpass.task.TaskSignal;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ChaoxingPlatformAdapter implements MoocPlatformAdapter {

    private static final String PLATFORM_CODE = "chaoxing";
    private static final String PLATFORM_NAME = "超星学习通";
    private static final String AES_KEY = "u2oh6Vu^HWe4_AES";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36";
    private static final String VIDEO_REFERER =
            "https://mooc1.chaoxing.com/ananas/modules/video/index.html?v=2025-0725-1842";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    @Autowired
    private CourseTaskMapper courseTaskMapper;

    @Autowired
    private QuestionAnswerService answers;

    @Autowired
    private TaskLogger logger;

    private final RateLimiter generalRateLimiter = new RateLimiter(500);
    private final RateLimiter videoLogRateLimiter = new RateLimiter(2000);

    private void saveProgress(CourseTask task, int progress, String chapterName) {
        if (task == null || task.isInspection()) return;
        task.setProgress(progress);
        if (StringUtils.hasText(chapterName)) {
            task.setCurrentChapter(chapterName);
        }
        task.setUpdateTime(java.time.LocalDateTime.now());
        if (courseTaskMapper != null) {
            try {
                courseTaskMapper.updateById(task);
            } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
                log.error("保存挂机进度到数据库失败: {}", e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public String getPlatformCode() { return PLATFORM_CODE; }

    @Override
    public String getPlatformName() { return PLATFORM_NAME; }

    @Override
    public boolean validateAccount(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) return false;
        try {
            OkHttpClient client = buildClient();
            return doLogin(client, username, password);
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("超星验证账号失败：", e);
            return false;
        }
    }

    @Override
    public boolean validateAuth(String token) {
        return false;
    }

    private String formatCoverUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String trimmed = url.trim();
        if (trimmed.startsWith("http://")) {
            return "https://" + trimmed.substring(7);
        }
        return trimmed;
    }

    private void parseChannelNode(JsonNode channel, List<CourseVO> list) {
        if (channel == null || channel.isMissingNode()) return;
        JsonNode content = channel.path("content");
        if (content.isMissingNode()) content = channel;

        if (content.has("subList")) {
            JsonNode subList = content.path("subList");
            if (subList.isArray()) {
                for (JsonNode sub : subList) {
                    parseChannelNode(sub, list);
                }
            }
        }

        String cpi = content.path("cpi").asText("");

        // Pattern 1: content.course.data array
        JsonNode courseData = content.path("course").path("data");
        if (courseData.isArray() && courseData.size() > 0) {
            for (JsonNode item : courseData) {
                String squareUrl = item.path("courseSquareUrl").asText("");
                String courseId = item.path("id").asText("");
                if (!StringUtils.hasText(courseId)) courseId = item.path("courseId").asText("");
                if (!StringUtils.hasText(courseId)) courseId = extractUrlParam(squareUrl, "courseId");

                String classId = content.path("id").asText("");
                if (!StringUtils.hasText(classId)) classId = content.path("key").asText("");
                if (!StringUtils.hasText(classId)) classId = extractUrlParam(squareUrl, "classId");

                if (!StringUtils.hasText(courseId)) continue;
                list.add(CourseVO.builder()
                        .courseId(courseId)
                        .classId(classId)
                        .cpi(cpi)
                        .name(item.path("name").asText("未命名课程"))
                        .teacher(item.path("teacherfactor").asText(""))
                        .coverUrl(formatCoverUrl(item.path("imageurl").asText("")))
                        .build());
            }
            return;
        }

        // Pattern 2: Direct course node on content (dtype="Course" or has id & clazz)
        String directId = content.path("id").asText("");
        if (StringUtils.hasText(directId) && (content.has("clazz") || "Course".equalsIgnoreCase(content.path("dtype").asText("")))) {
            String classId = "";
            JsonNode clazzNode = content.path("clazz");
            if (clazzNode.isArray() && clazzNode.size() > 0) {
                classId = clazzNode.get(0).path("clazzId").asText("");
            }
            list.add(CourseVO.builder()
                    .courseId(directId)
                    .classId(classId)
                    .cpi(cpi)
                    .name(content.path("name").asText("未命名课程"))
                    .teacher(content.path("teacherfactor").asText(""))
                    .coverUrl(formatCoverUrl(content.path("imageurl").asText("")))
                    .build());
        }
    }

    public Integer fetchCourseProgress(OkHttpClient client, String courseId, String classId, String cpi) {
        if (!StringUtils.hasText(courseId) || !StringUtils.hasText(classId)) return null;
        try {
            String url = String.format(
                    "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&cpi=%s&ut=s",
                    courseId, classId, StringUtils.hasText(cpi) ? cpi : "");
            Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String html = resp.body().string();
                    Document doc = Jsoup.parse(html);
                    Element h2 = doc.selectFirst("h2.xs_head_name");
                    if (h2 != null) {
                        Matcher m = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(h2.text());
                        if (m.find()) {
                            int done = Integer.parseInt(m.group(1));
                            int total = Integer.parseInt(m.group(2));
                            return total > 0 ? Math.min(100, (int) (done * 100.0 / total)) : 0;
                        }
                    }
                    Elements taskDivs = doc.select(".catalog_task div");
                    if (!taskDivs.isEmpty()) {
                        int total = taskDivs.size();
                        long done = taskDivs.stream().filter(d -> d.className().contains("icon_yiwanc")).count();
                        return (int) Math.min(100, (done * 100 / total));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取超星课程进度异常 courseId={} classId={}: {}", courseId, classId, e.getMessage());
        }
        return null;
    }

    public Map<String, Integer> fetchStuJobInfo(OkHttpClient client, List<CourseVO> courses) {
        Map<String, Integer> progressMap = new HashMap<>();
        if (courses == null || courses.isEmpty()) return progressMap;

        List<String> pairs = new ArrayList<>();
        for (CourseVO c : courses) {
            if (StringUtils.hasText(c.getClassId()) && StringUtils.hasText(c.getCpi())) {
                pairs.add(c.getClassId() + "_" + c.getCpi());
            }
        }
        if (pairs.isEmpty()) return progressMap;

        int batchSize = 25;
        for (int i = 0; i < pairs.size(); i += batchSize) {
            List<String> chunk = pairs.subList(i, Math.min(pairs.size(), i + batchSize));
            String param = String.join(",", chunk);
            String url = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu-job-info?clazzPersonStr=" + param;
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselist")
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonNode root = objectMapper.readTree(resp.body().string());
                    if (root.path("status").asBoolean(false)) {
                        JsonNode arr = root.path("jobArray");
                        if (arr.isArray()) {
                            for (JsonNode item : arr) {
                                int jobCount = item.path("jobCount").asInt(0);
                                if (jobCount > 0) {
                                    String clazzId = item.path("clazzId").asText("");
                                    double rate = item.path("jobRate").asDouble(0.0);
                                    progressMap.put(clazzId, (int) Math.round(rate));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取超星任务点进度失败: {}", e.getMessage());
            }
        }
        return progressMap;
    }

    @Override
    public List<CourseVO> getCourseList(UserPlatformConfig config) {
        List<CourseVO> list = new ArrayList<>();
        if (config == null) return list;
        String username = config.getUsername();
        String password = config.getPassword();
        try {
            OkHttpClient client = buildClient();
            if (!doLogin(client, username, password))
                throw new RuntimeException("学习通登录失败，请检查账号密码");
            Request request = new Request.Builder()
                    .url("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=")
                    .header("User-Agent", USER_AGENT)
                    .get().build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonNode root = objectMapper.readTree(response.body().string());
                    JsonNode channelList = root.path("channelList");
                    if (channelList.isArray()) {
                        for (JsonNode channel : channelList) {
                            parseChannelNode(channel, list);
                        }
                    }
                }
            }

            if (list.isEmpty()) {
                String[] fallbackUrls = new String[]{
                        "https://mooc1-1.chaoxing.com/visit/courselistdata",
                        "https://mooc1-2.chaoxing.com/visit/courselistdata",
                        "https://mooc1.chaoxing.com/visit/courselistdata"
                };
                for (String fallbackUrl : fallbackUrls) {
                    try {
                        Request htmlReq = new Request.Builder()
                                .url(fallbackUrl)
                                .header("User-Agent", USER_AGENT)
                                .get().build();
                        try (Response htmlResp = client.newCall(htmlReq).execute()) {
                            if (htmlResp.isSuccessful() && htmlResp.body() != null) {
                                Document doc = Jsoup.parse(htmlResp.body().string());
                                Elements courseElems = doc.select("li.course, div.course, li.courseItem, div.courseItem");
                                for (Element elem : courseElems) {
                                    String courseId = elem.attr("courseid");
                                    if (!StringUtils.hasText(courseId)) courseId = elem.attr("data-courseid");
                                    String classId = elem.attr("clazzid");
                                    if (!StringUtils.hasText(classId)) classId = elem.attr("data-clazzid");
                                    String name = elem.select("span.course-name, h3.clearfix, a.course-name, h3, div.name").text();
                                    String teacher = elem.select("p.teacher, span.teacher, div.teacher").text();
                                    String coverUrl = formatCoverUrl(elem.select("img").attr("src"));
                                    if (StringUtils.hasText(courseId)) {
                                        list.add(CourseVO.builder()
                                                .courseId(courseId)
                                                .classId(classId)
                                                .name(name)
                                                .teacher(teacher)
                                                .coverUrl(coverUrl)
                                                .build());
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        ExecutionScope.rethrowControl(ignored);
                    }
                    if (!list.isEmpty()) break;
                }
            }

            if (!list.isEmpty()) {
                Map<String, CourseVO> uniqueMap = new LinkedHashMap<>();
                for (CourseVO c : list) {
                    uniqueMap.put(c.getCourseId() + "_" + c.getClassId(), c);
                }
                list = new ArrayList<>(uniqueMap.values());

                // 依据抓包接口 stu-job-info 批量获取任务点进度并过滤：仅显示有任务点进度的课程
                Map<String, Integer> progressMap = fetchStuJobInfo(client, list);
                List<CourseVO> withJobs = new ArrayList<>();
                for (CourseVO c : list) {
                    Integer prog = progressMap.get(c.getClassId());
                    if (prog != null) {
                        c.setPlatformProgress(prog);
                        withJobs.add(c);
                    }
                }
                if (!withJobs.isEmpty()) {
                    list = withJobs;
                }
            }

        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("获取超星课程列表失败：", e);
            throw new RuntimeException("获取超星课程列表失败：" + e.getClass().getSimpleName());
        }
        return list;
    }

    @Override
    public void executeCourseTask(CourseTask task, UserPlatformConfig config,
                                  UserQuestionConfig ignoredConfig, QuestionSolver ignoredSolver, TaskLogger taskLogger) {
        ExecutionScope scope = ExecutionScope.current();
        try {
            OkHttpClient client = buildClient();
            if (!doLogin(client, config.getUsername(), config.getPassword()))
                throw new TaskSignal("WAITING_AUTH", "PLATFORM_AUTH_EXPIRED", "平台登录失效，请重新认证");
            String uid = getCookieValue(client, "https://passport2.chaoxing.com", "_uid");
            if (!StringUtils.hasText(uid)) uid = getCookieValue(client, "https://passport2.chaoxing.com", "UID");
            if (!StringUtils.hasText(uid)) throw new TaskSignal("WAITING_AUTH", "PLATFORM_IDENTITY_MISSING", "平台未返回用户身份");
            String fid = fetchFid(client);
            String cpi = fetchCpi(client, task.getCourseId(), task.getClassId());
            List<KnowledgePoint> points = fetchKnowledgePoints(client, task.getCourseId(), task.getClassId(), cpi);
            if (points.isEmpty()) throw new TaskSignal("WAITING_USER", "COURSE_NOT_OPEN", "未读取到课程章节，请核对课程是否开放");
            for (int index = 0; index < points.size(); index++) {
                ExecutionScope.check();
                KnowledgePoint point = points.get(index);
                JobFetchResult jobs = fetchJobs(client, task, point, cpi, fid, taskLogger);
                for (JobPoint job : jobs.jobs) {
                    String id = resourceId(job);
                    if (!scope.includes(id, point.id, job.type)) continue;
                    if (job.completed) {
                        taskLogger.log(task.getId(), "跳过已完成资源：" + job.name);
                        continue;
                    }
                    scope.begin(id);
                    processSingleJob(client, task, job, cpi, fid, uid, jobs, ignoredConfig, ignoredSolver, taskLogger);
                    scope.checkpoint("EXECUTED");
                }
                scope.progress(Math.min(99, (index + 1) * 100 / points.size()), point.title);
            }
            task.setStatus("VERIFYING");
        } catch (TaskSignal signal) {
            throw signal;
        } catch (Exception error) {
            ExecutionScope.rethrowControl(error);
            throw new TaskSignal("RETRY_WAIT", "PLATFORM_REQUEST_FAILED", "课程执行中断，等待重试并核验平台进度");
        }
    }

    @Override
    public List<CourseResource> getResources(UserPlatformConfig config, CourseVO course) {
        return getResources(config, course, (completed, total) -> {});
    }

    @Override
    public List<CourseResource> getResources(UserPlatformConfig config, CourseVO course,
                                            java.util.function.BiConsumer<Integer, Integer> progress) {
        try {
            OkHttpClient client = buildClient();
            if (!doLogin(client, config.getUsername(), config.getPassword()))
                throw new TaskSignal("WAITING_AUTH", "PLATFORM_AUTH_EXPIRED", "平台登录失效，请重新认证");
            CourseTask task = CourseTask.builder().courseId(course.getCourseId()).classId(course.getClassId()).inspection(true).build();
            String fid = fetchFid(client), cpi = fetchCpi(client, course.getCourseId(), course.getClassId());
            course.setPlatformProgress(fetchCoursePlatformProgress(client, course.getCourseId(), course.getClassId(), cpi));
            List<CourseResource> result = new ArrayList<>();
            List<KnowledgePoint> points = fetchKnowledgePoints(client, course.getCourseId(), course.getClassId(), cpi);
            progress.accept(0, points.size());
            int completed = 0;
            for (KnowledgePoint point : points) {
                for (JobPoint job : fetchJobs(client, task, point, cpi, fid, logger).jobs) {
                    result.add(new CourseResource(resourceId(job), point.id, point.title + " / " + job.name, job.type, job.completed));
                }
                progress.accept(++completed, points.size());
            }
            return result.stream().collect(java.util.stream.Collectors.toMap(CourseResource::id, r -> r,
                (first, second) -> second, LinkedHashMap::new)).values().stream().toList();
        } catch (TaskSignal signal) { throw signal; }
        catch (Exception error) {
            ExecutionScope.rethrowControl(error); throw new TaskSignal("RETRY_WAIT", "COURSE_SYNC_FAILED", "课程章节同步失败"); }
    }

    private String resourceId(JobPoint job) {
        return job.knowledgeId + "/" + (StringUtils.hasText(job.jobId) ? job.jobId : job.type + ":" + job.objectId);
    }

    private void processSingleJob(OkHttpClient client, CourseTask task, JobPoint job,
                                  String cpi, String fid, String uid, JobFetchResult jobs,
                                  UserQuestionConfig config, QuestionSolver solver, TaskLogger logger) {
        ExecutionScope.check();
        logger.log(task.getId(), "处理资源：" + job.name);
        switch (job.type) {
            case "video", "audio" -> studyVideo(client, task, job, cpi, fid, uid, logger);
            case "document" -> studyDocument(client, task, job, logger);
            case "read" -> studyRead(client, task, job, jobs.jobInfo, logger);
            case "workid" -> studyWork(client, task, job, jobs.jobInfo, config, solver, logger);
            default -> throw new TaskSignal("WAITING_USER", "RESOURCE_UNSUPPORTED", "该资源类型暂未支持");
        }
        ExecutionScope.sleep(500);
    }

    private void studyVideo(OkHttpClient client, CourseTask task, JobPoint job,
                            String cpi, String fid, String userId, TaskLogger taskLogger) {
        try {

            String statusUrl = String.format(
                    "https://mooc1.chaoxing.com/ananas/status/%s?k=%s&flag=normal",
                    job.objectId, fid);
            Request statusReq = new Request.Builder()
                    .url(statusUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", VIDEO_REFERER)
                    .get().build();

            int duration = 60;
            String dtoken = "";
            String rt = job.rt; 
            String videoName = StringUtils.hasText(job.name) ? job.name : "未命名视频";
            int playTime = Math.max(0, job.playTime / 1000);
            try (Response resp = client.newCall(statusReq).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    taskLogger.log(task.getId(), String.format("[视频] 《%s》 获取视频状态失败，等待重试。", videoName));
                    throw new TaskSignal("RETRY_WAIT", "VIDEO_STATUS_FAILED", "视频状态获取失败，等待重试");
                }
                JsonNode statusJson = objectMapper.readTree(resp.body().string());
                if (!"success".equals(statusJson.path("status").asText())) {
                    taskLogger.log(task.getId(), String.format("[视频] 《%s》 视频状态异常，等待重试。", videoName));
                    throw new TaskSignal("RETRY_WAIT", "VIDEO_STATUS_FAILED", "视频状态异常，等待重试");
                }
                dtoken   = statusJson.path("dtoken").asText("");
                duration = statusJson.path("duration").asInt(0);
                int statusPlayTime = statusJson.path("playTime").asInt(job.playTime) / 1000;
                if (statusPlayTime > 0 && statusPlayTime < duration) playTime = statusPlayTime;
                if (duration <= 0) throw new TaskSignal("WAITING_USER", "DURATION_UNKNOWN", "平台未返回有效媒体时长");
            }

            double speed = (task.getSpeed() != null) ? task.getSpeed() : 1.0;

            taskLogger.log(task.getId(), String.format(
                    "[视频] 《%s》 初始化成功: 总时长 %ds | 初始已播 %ds | 倍速 %.1fx", videoName, duration, playTime, speed));

            int lastLogTime = 0;
            int waitTime = Math.max(30, job.reportTimeInterval > 0 ? job.reportTimeInterval : 60);
            boolean passed = false;
            int forbiddenRetry = 0;
            final int maxForbiddenRetry = 2;
            int endRetryCount = 0;
            long lastIter = System.currentTimeMillis();

            while (!passed) {
                if (Thread.currentThread().isInterrupted()) return;

                if (playTime - lastLogTime >= waitTime || playTime >= duration) {
                    int isdrag = playTime >= duration ? 4 : (lastLogTime <= 0 ? 3 : 2);
                    VideoLogResult result = reportVideoLog(client, task, job, cpi, userId,
                            dtoken, duration, playTime, rt, isdrag, taskLogger);
                    if (result.statusCode == 403) {
                        if (forbiddenRetry >= maxForbiddenRetry) {
                            taskLogger.log(task.getId(), String.format("[视频] 《%s》 403 重试失败，等待重试。", videoName));
                            throw new TaskSignal("RETRY_WAIT", "VIDEO_REPORT_FORBIDDEN", "视频进度上报被平台拒绝，等待重试");
                        }
                        forbiddenRetry++;
                        taskLogger.log(task.getId(), String.format("[视频] 《%s》 遇到 403 报错，尝试恢复会话状态 (第%d次)", videoName, forbiddenRetry));
                        sleepRandom(2000, 4000);
                        JsonNode refreshed = refreshVideoStatus(client, job.objectId, fid);
                        if (refreshed != null && refreshed.has("dtoken")) {
                            dtoken = refreshed.path("dtoken").asText(dtoken);
                        }
                        continue;
                    } else if (!result.isPassed && result.statusCode != 200) {
                        taskLogger.log(task.getId(), String.format("[视频] 《%s》 上报异常(状态码:%d)，等待重试。", videoName, result.statusCode));
                        throw new TaskSignal("RETRY_WAIT", "VIDEO_REPORT_FAILED", "视频进度上报失败，等待重试");
                    }

                    passed = result.isPassed;
                    lastLogTime = playTime;
                    waitTime = Math.max(30, job.reportTimeInterval > 0 ? job.reportTimeInterval : 60);

                    taskLogger.log(task.getId(), String.format(
                            "[视频] 《%s》 播放进度上报: %ds / %ds (%d%%)",
                            videoName, playTime, duration, Math.min(100, playTime * 100 / Math.max(1, duration))));

                    if (passed) {
                        break;
                    } else if (playTime >= duration) {
                        endRetryCount++;
                        if (endRetryCount > 6) throw new TaskSignal("RETRY_WAIT", "PROGRESS_UNCONFIRMED", "平台暂未确认视频完成");
                        taskLogger.log(task.getId(), String.format("[视频] 《%s》 播放已达终点，正在等待超星服务端完成结算 (第%d次验证)...", videoName, endRetryCount));
                        if (endRetryCount % 2 == 0) {
                            JsonNode statusObj = refreshVideoStatus(client, job.objectId, fid);
                            if (statusObj != null) {
                                if ("success".equals(statusObj.path("status").asText()) && statusObj.path("isPassed").asBoolean(false)) {
                                    break;
                                }
                                if (statusObj.has("dtoken")) {
                                    dtoken = statusObj.path("dtoken").asText(dtoken);
                                }
                            }
                        }
                        sleepRandom(1500, 2500);
                    }
                }

                sleepRandom(950, 1050);
                long now = System.currentTimeMillis();
                double dt = ((now - lastIter) / 1000.0) * speed;
                lastIter = now;
                playTime = Math.min(duration, playTime + (int) Math.max(1, Math.round(dt)));
            }

            taskLogger.log(task.getId(), String.format("[视频] 《%s》 视频任务学习完成！", videoName));
        } catch (TaskSignal signal) {
            throw signal;
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("视频任务异常：", e);
            String vName = (job != null && StringUtils.hasText(job.name)) ? job.name : "未命名视频";
            taskLogger.log(task.getId(), String.format("[视频] 《%s》 视频任务异常：%s", vName, e.getClass().getSimpleName()));
            throw new TaskSignal("RETRY_WAIT", "VIDEO_PROCESS_FAILED", "视频任务处理异常，等待重试");
        }
    }

    private JsonNode refreshVideoStatus(OkHttpClient client, String objectId, String fid) {
        try {
            String infoUrl = String.format("https://mooc1.chaoxing.com/ananas/status/%s?k=%s&flag=normal", objectId, fid);
            Request req = new Request.Builder()
                    .url(infoUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", VIDEO_REFERER)
                    .get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    return objectMapper.readTree(resp.body().string());
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("刷新视频状态异常: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    private VideoLogResult reportVideoLog(OkHttpClient client, CourseTask task, JobPoint job,

                                          String cpi, String userId, String dtoken,
                                          int duration, int playingTime, String rt, int isdrag,
                                          TaskLogger taskLogger) {
        try {
            videoLogRateLimiter.limitRate(true, 0, 2000);
            String rawSign = String.format("[%s][%s][%s][%s][%d][d_yHJ!$pdA~5][%d][0_%d]",
                    task.getClassId(), userId, job.jobId, job.objectId,
                    (long) playingTime * 1000, (long) duration * 1000, duration);
            String enc = DigestUtils.md5DigestAsHex(rawSign.getBytes(StandardCharsets.UTF_8));

            HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(
                            "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/" + cpi + "/" + dtoken)).newBuilder()
                    .addQueryParameter("clazzId",    task.getClassId())
                    .addQueryParameter("playingTime", String.valueOf(playingTime))
                    .addQueryParameter("duration",    String.valueOf(duration))
                    .addQueryParameter("clipTime",    "0_" + duration)
                    .addQueryParameter("objectId",    job.objectId)
                    .addQueryParameter("otherInfo",   job.otherInfo)
                    .addQueryParameter("courseId",    task.getCourseId())
                    .addQueryParameter("jobid",       job.jobId)
                    .addQueryParameter("userid",      userId)
                    .addQueryParameter("isdrag",      String.valueOf(isdrag))
                    .addQueryParameter("view",        "pc")
                    .addQueryParameter("enc",         enc)
                    .addQueryParameter("dtype",       "Video");

            if (StringUtils.hasText(job.videoFaceCaptureEnc))
                urlBuilder.addQueryParameter("videoFaceCaptureEnc", job.videoFaceCaptureEnc);
            if (StringUtils.hasText(job.attDuration))
                urlBuilder.addQueryParameter("attDuration", job.attDuration);
            if (StringUtils.hasText(job.attDurationEnc))
                urlBuilder.addQueryParameter("attDurationEnc", job.attDurationEnc);

            String resolvedRt = resolveRt(rt, job.otherInfo);

            if (StringUtils.hasText(resolvedRt)) {
                urlBuilder.addQueryParameter("rt", resolvedRt)
                        .addQueryParameter("_t", String.valueOf(System.currentTimeMillis()));

                Request req = new Request.Builder()
                        .url(urlBuilder.build())
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", VIDEO_REFERER)
                        .get().build();

                try (Response resp = client.newCall(req).execute()) {
                    return parseVideoLogResponse(client, req, resp, taskLogger, task != null ? task.getId() : null);
                }
            } else {
                for (String tryRt : new String[]{"0.9", "1"}) {
                    HttpUrl url = urlBuilder
                            .removeAllQueryParameters("rt")
                            .removeAllQueryParameters("_t")
                            .addQueryParameter("rt", tryRt)
                            .addQueryParameter("_t", String.valueOf(System.currentTimeMillis()))
                            .build();

                    Request req = new Request.Builder()
                            .url(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", VIDEO_REFERER)
                            .get().build();

                    try (Response resp = client.newCall(req).execute()) {
                        VideoLogResult res = parseVideoLogResponse(client, req, resp, taskLogger, task != null ? task.getId() : null);
                        if (res.statusCode == 200) return res;
                        if (res.statusCode == 403) {
                            log.warn("视频上报 rt={} 返回 403，尝试切换", tryRt);
                        }
                    }
                }
                return new VideoLogResult(false, 403);
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("视频进度上报异常：", e);
            return new VideoLogResult(false, -1);
        }
    }

    private String resolveRt(String rt, String otherInfo) {
        if (StringUtils.hasText(rt)) return rt;
        if (StringUtils.hasText(otherInfo)) {
            Matcher m = Pattern.compile("-rt_([1d])").matcher(otherInfo);
            if (m.find()) {
                return "d".equals(m.group(1)) ? "0.9" : "1";
            }
        }
        return "";
    }

    private VideoLogResult parseVideoLogResponse(OkHttpClient client, Request originalReq, Response resp, TaskLogger taskLogger, Long taskId) throws Exception {
        if (resp.body() != null) {
            String bodyStr = resp.body().string();
            if (checkNeedCaptcha(bodyStr)) {
                boolean solved = handleCaptchaPass(client, taskLogger, taskId);
                if (solved) {
                    try (Response retryResp = client.newCall(originalReq).execute()) {
                        if (retryResp.isSuccessful() && retryResp.body() != null) {
                            JsonNode json = objectMapper.readTree(retryResp.body().string());
                            return new VideoLogResult(json.path("isPassed").asBoolean(false), 200);
                        }
                    }
                }
            }
            if (resp.isSuccessful()) {
                try {
                    JsonNode json = objectMapper.readTree(bodyStr);
                    return new VideoLogResult(json.path("isPassed").asBoolean(false), 200);
                } catch (Exception ignored) {
            ExecutionScope.rethrowControl(ignored);}
            }
        }
        return new VideoLogResult(false, resp.code());
    }

    private void studyDocument(OkHttpClient client, CourseTask task, JobPoint job, TaskLogger taskLogger) {
        try {
            String knowledgeid = StringUtils.hasText(job.knowledgeId) ? job.knowledgeId : extractNodeId(job.otherInfo);

            HttpUrl.Builder urlBuilder = Objects.requireNonNull(
                            HttpUrl.parse("https://mooc1.chaoxing.com/mooc-ans/job/document")).newBuilder()
                    .addQueryParameter("jobid",       job.jobId)
                    .addQueryParameter("knowledgeid", knowledgeid)
                    .addQueryParameter("courseid",    task.getCourseId())
                    .addQueryParameter("clazzid",     task.getClassId())
                    .addQueryParameter("checkMicroTopic", "true")
                    .addQueryParameter("microTopicId", "")
                    .addQueryParameter("courseEngineInfo", "false")
                    .addQueryParameter("_dc",         String.valueOf(System.currentTimeMillis()));
            if (StringUtils.hasText(job.jtoken))
                urlBuilder.addQueryParameter("jtoken", job.jtoken);

            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://mooc1.chaoxing.com/ananas/modules/pdf/index.html?v=2026-0826-1905")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get().build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    taskLogger.log(task.getId(), "[文档] 文档任务点打卡失败，状态码：" + response.code());
                    throw new TaskSignal("RETRY_WAIT", "DOCUMENT_STUDY_FAILED", "文档任务点打卡失败，等待重试");
                }
                String body = response.body().string();
                JsonNode json = objectMapper.readTree(body);
                if (!truthy(json, "status", "success", "result")) {
                    taskLogger.log(task.getId(), "[文档] 文档任务点打卡未被平台确认：" + truncate(body, 120));
                    throw new TaskSignal("RETRY_WAIT", "DOCUMENT_STUDY_FAILED", "文档任务点打卡未被平台确认，等待重试");
                }
                taskLogger.log(task.getId(), "[文档] 文档任务点打卡成功：" + json.path("msg").asText(""));
            }
        } catch (TaskSignal signal) {
            throw signal;
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            taskLogger.log(task.getId(), "[文档] 文档任务处理异常：" + e.getClass().getSimpleName());
            throw new TaskSignal("RETRY_WAIT", "DOCUMENT_STUDY_FAILED", "文档任务处理异常，等待重试");
        }
    }

    private void studyRead(OkHttpClient client, CourseTask task, JobPoint job,
                           JobInfo jobInfo, TaskLogger taskLogger) {
        try {
            HttpUrl url = Objects.requireNonNull(
                            HttpUrl.parse("https://mooc1.chaoxing.com/ananas/job/readv2")).newBuilder()
                    .addQueryParameter("jobid",       job.jobId)
                    .addQueryParameter("knowledgeid", StringUtils.hasText(job.knowledgeId) ? job.knowledgeId : (jobInfo != null ? jobInfo.knowledgeid : ""))
                    .addQueryParameter("jtoken",      job.jtoken)
                    .addQueryParameter("courseid",    task.getCourseId())
                    .addQueryParameter("clazzid",     task.getClassId())
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get().build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonNode json = objectMapper.readTree(response.body().string());
                    taskLogger.log(task.getId(), "[阅读] 阅读任务完成：" + json.path("msg").asText());
                } else {
                    taskLogger.log(task.getId(), "[阅读] 阅读任务失败，状态码：" + response.code());
                    throw new TaskSignal("RETRY_WAIT", "READ_STUDY_FAILED", "阅读任务打卡失败，等待重试");
                }
            }
        } catch (TaskSignal signal) {
            throw signal;
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            taskLogger.log(task.getId(), "[阅读] 阅读任务处理异常：" + e.getClass().getSimpleName());
            throw new TaskSignal("RETRY_WAIT", "READ_STUDY_FAILED", "阅读任务处理异常，等待重试");
        }
    }

    private void studyWork(OkHttpClient client, CourseTask task, JobPoint job,
                           JobInfo jobInfo, UserQuestionConfig questionConfig,
                           QuestionSolver questionSolver, TaskLogger taskLogger) {
        taskLogger.log(task.getId(), "[测验] 正在拉取测验题目...");

        try {
            String workId = job.jobId.replace("work-", "");
            String workKnowledgeId = StringUtils.hasText(job.knowledgeId)
                    ? job.knowledgeId
                    : (jobInfo != null ? jobInfo.knowledgeid : "");
            String workCpi = StringUtils.hasText(job.cpi)
                    ? job.cpi
                    : ((jobInfo != null && StringUtils.hasText(jobInfo.cpi)) ? jobInfo.cpi : "");
            String workKtoken = StringUtils.hasText(job.ktoken)
                    ? job.ktoken
                    : (jobInfo != null ? jobInfo.ktoken : "");
            HttpUrl.Builder urlBuilder = Objects.requireNonNull(
                            HttpUrl.parse("https://mooc1.chaoxing.com/mooc-ans/api/work")).newBuilder()
                    .addQueryParameter("api",           "1")
                    .addQueryParameter("workId",        workId)
                    .addQueryParameter("jobid",         job.jobId)
                    .addQueryParameter("originJobId",   job.jobId)
                    .addQueryParameter("needRedirect",  "true")
                    .addQueryParameter("skipHeader",    "true")
                    .addQueryParameter("knowledgeid",   workKnowledgeId)
                    .addQueryParameter("cpi",           workCpi)
                    .addQueryParameter("ut",            "s")
                    .addQueryParameter("clazzId",       task.getClassId())
                    .addQueryParameter("type",          "")
                    .addQueryParameter("enc",           job.enc != null ? job.enc : "")
                    .addQueryParameter("mooc2",         "1")
                    .addQueryParameter("courseid",      task.getCourseId());

            if (StringUtils.hasText(workKtoken)) {
                urlBuilder.addQueryParameter("ktoken", workKtoken);
            }

            HttpUrl workUrl = urlBuilder.build();
            Request workReq = new Request.Builder()
                    .url(workUrl)
                    .header("User-Agent", USER_AGENT)
                    .get().build();
            String html = executeStringWithCaptchaCheck(client, workReq, taskLogger, task.getId());
            if (html != null && html.contains("教师未创建完成该测验")) {
                taskLogger.log(task.getId(), "教师未创建完成该测验，等待用户确认。");
                throw new TaskSignal("WAITING_USER", "WORK_NOT_READY", "教师未创建完成该测验");
            }

            if (html == null) {
                taskLogger.log(task.getId(), "拉取题目失败，等待重试。");
                throw new TaskSignal("RETRY_WAIT", "WORK_FETCH_FAILED", "拉取测验题目失败，等待重试");
            }

            WorkQuestions workData = parseWorkHtml(html);
            if (workData == null || workData.questions.isEmpty()) {
                taskLogger.log(task.getId(), "未解析到题目内容，等待人工核对。");
                throw new TaskSignal("WAITING_USER", "WORK_PARSE_EMPTY", "未解析到测验题目内容");
            }

            taskLogger.log(task.getId(), "共解析到 " + workData.questions.size() + " 道题目");

            boolean autoAnswer = task.getAutoAnswer() == null || task.getAutoAnswer();
            if (!autoAnswer) {
                ExecutionScope.current().requireReview();
                taskLogger.log(task.getId(), "用户配置已关闭自动答题，测验不提交。");
                return;
            }

            double coverRate = task.getCoverRate() != null ? task.getCoverRate()
                    : ((questionConfig != null && questionConfig.getCoverRate() != null) ? questionConfig.getCoverRate() : 0.8);

            int found = 0;
            List<String> unresolved = new ArrayList<>();
            ExecutionScope scope = ExecutionScope.current();
            for (WorkQuestion q : workData.questions) {
                if (!q.decoded) {
                    taskLogger.log(task.getId(), "[答题] 字体部分解码，将尝试由 AI 根据题意推断：" + truncate(q.title, 50));
                }
                var solved = answers.solve("chaoxing", q.id, q.type, q.title, q.options, q.completionCount, !q.decoded);
                var match = solved.match();
                boolean confident = match.matched() && !match.needsReview();
                if (!confident) {
                    unresolved.add(truncate(q.title, 50));
                    taskLogger.log(task.getId(), "[答题] 未稳定匹配题目，已留空：" + truncate(q.title, 80));
                    continue;
                }
                if ("AI_GUESS".equals(solved.source()))
                    taskLogger.log(task.getId(), "[答题] AI 已给出最可能答案：" + truncate(q.title, 50) + " -> " + match.value());
                if ("AI_PARTIAL".equals(solved.source()))
                    taskLogger.log(task.getId(), "[答题] AI 已基于部分解码内容给出答案：" + truncate(q.title, 50) + " -> " + match.value());
                found++;
                if ("completion".equals(q.type)) {
                    workData.formData.remove("answer" + q.id);
                    workData.formData.put("tiankongsize" + q.id, String.valueOf(Math.max(1, q.completionCount)));
                    for (int i = 0; i < match.blanks().size(); i++)
                        workData.formData.put("answerEditor" + q.id + (i + 1),
                            "<p>" + org.jsoup.nodes.Entities.escape(match.blanks().get(i)) + "</p>");
                } else workData.formData.put("answer" + q.id, match.value());
            }
            boolean incomplete = !unresolved.isEmpty() || (double) found / workData.questions.size() < coverRate;
            if (incomplete) {
                taskLogger.log(task.getId(), String.format("[答题] %d 道题无法稳定匹配，已留空；本次不自动提交。", unresolved.size()));
            }
            if (found == 0) {
                scope.markAnswerPending("测验题目均未稳定匹配，未向平台提交");
                return;
            }
            boolean doSubmit = "SUBMIT".equals(scope.settings().answerMode()) && !incomplete;
            workData.formData.put("pyFlag", doSubmit ? "" : "1");

            scope.checkpoint("SUBMITTING");
            SubmitResult result = submitWorkForm(client, workData.formData, "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew");
            if (!result.success) {
                result = submitWorkForm(client, workData.formData, "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNewWeb");
            }
            if (result.success) {
                scope.checkpoint("ACKNOWLEDGED");
                if (!doSubmit) scope.markAnswerPending(incomplete
                    ? "已保存可匹配答案，未匹配题目留空，测验未提交"
                    : "答案已保存至平台，测验尚未提交");
                taskLogger.log(task.getId(), "[答题] " + (doSubmit ? "提交" : "保存") + "答题成功：" + result.message);
            } else {
                taskLogger.log(task.getId(), "[答题] 平台未接受本次答案：" + result.message);
                throw new TaskSignal("WAITING_USER", "ANSWER_REJECTED", "平台未接受本次答案，请检查任务详情");
            }

        } catch (TaskSignal signal) { throw signal; }
        catch (Exception error) {
            ExecutionScope.rethrowControl(error);
            throw new TaskSignal("WAITING_USER", "SUBMISSION_UNCERTAIN", "练习处理结果不确定，请到平台核对后继续");
        }
    }

    private SubmitResult submitWorkForm(OkHttpClient client, Map<String, String> formData, String endpoint) {
        try {
            FormBody.Builder formBuilder = new FormBody.Builder();
            for (Map.Entry<String, String> entry : formData.entrySet()) {
                formBuilder.add(entry.getKey(), entry.getValue() != null ? entry.getValue() : "");
            }
            Request submitReq = new Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", "https://mooc1.chaoxing.com")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .post(formBuilder.build())
                    .build();
            try (Response response = client.newCall(submitReq).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return new SubmitResult(false, "HTTP " + response.code());
                }
                String body = response.body().string();
                JsonNode json = objectMapper.readTree(body);
                boolean status = truthy(json, "status", "result", "success");
                String msg = json.path("msg").asText(json.path("message").asText(""));
                return new SubmitResult(status, StringUtils.hasText(msg) ? msg : body);
            }
        } catch (Exception error) {
            ExecutionScope.rethrowControl(error);
            return new SubmitResult(false, error.getClass().getSimpleName());
        }
    }

    private boolean doLogin(OkHttpClient client, String username, String password) throws Exception {
        FormBody formBody = new FormBody.Builder()
                .add("fid",               "-1")
                .add("uname",             aesEncrypt(username, AES_KEY))
                .add("password",          aesEncrypt(password, AES_KEY))
                .add("refer",             "https://i.chaoxing.com")
                .add("t",                 "true")
                .add("forbidotherlogin",  "0")
                .add("validate",          "")
                .add("doubleFactorLogin", "0")
                .add("independentId",     "0")
                .build();

        Request request = new Request.Builder()
                .url("https://passport2.chaoxing.com/fanyalogin")
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Referer", "https://passport2.chaoxing.com/login?fid=-1&refer=https%3A%2F%2Fi.chaoxing.com")
                .header("Origin", "https://passport2.chaoxing.com")
                .post(formBody)
                .build();

        OkHttpClient loginClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();

        boolean loginSuccess = false;
        try (Response response = loginClient.newCall(request).execute()) {
            if (response.code() == 200 && response.body() != null) {
                String body = response.body().string();
                try {
                    JsonNode json = objectMapper.readTree(body);
                    loginSuccess = json.path("status").asBoolean(false);
                    if (!loginSuccess) {
                        log.warn("超星登录失败：{}", body);
                        return false;
                    }
                } catch (Exception e) {
                    String uid = getCookieValue(client, "https://passport2.chaoxing.com", "_uid");
                    if (!StringUtils.hasText(uid)) {
                        uid = getCookieValue(client, "https://chaoxing.com", "_uid");
                    }
                    loginSuccess = StringUtils.hasText(uid);
                    if (!loginSuccess) {
                        log.warn("超星登录返回非JSON内容且未检测到登录凭据：{}", body);
                        return false;
                    }
                }
            } else if (response.code() == 301 || response.code() == 302) {
                String location = response.header("Location");
                String uid = getCookieValue(client, "https://passport2.chaoxing.com", "_uid");
                if (!StringUtils.hasText(uid)) {
                    uid = getCookieValue(client, "https://chaoxing.com", "_uid");
                }
                loginSuccess = StringUtils.hasText(uid) || (location != null && !location.contains("login"));
                if (!loginSuccess) {
                    log.warn("超星登录重定向失败，Location: {}", location);
                    return false;
                }
            } else {
                log.warn("超星登录请求返回非预期HTTP状态码: {}", response.code());
                return false;
            }
        }

        if (loginSuccess) {
            try {
                Request initReq = new Request.Builder()
                        .url("https://mooc1-1.chaoxing.com/visit/courselistdata")
                        .header("User-Agent", USER_AGENT)
                        .get().build();
                try (Response r = client.newCall(initReq).execute()) {}
            } catch (Exception ignored) {
                ExecutionScope.rethrowControl(ignored);
            }
            return true;
        }
        return false;
    }

    private String fetchFid(OkHttpClient client) {
        String fid = getCookieValue(client, "https://chaoxing.com", "fid");
        if (!StringUtils.hasText(fid)) {
            fid = getCookieValue(client, "https://passport2.chaoxing.com", "fid");
        }
        if (!StringUtils.hasText(fid)) {
            fid = getCookieValue(client, "https://i.chaoxing.com", "fid");
        }
        if (StringUtils.hasText(fid)) return fid;

        try {
            OkHttpClient noRedirectClient = client.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
            Request req = new Request.Builder()
                    .url("https://i.chaoxing.com/base")
                    .header("User-Agent", USER_AGENT)
                    .get().build();
            try (Response resp = noRedirectClient.newCall(req).execute()) {
                if (resp.body() != null) {
                    String html = resp.body().string();
                    Matcher m = Pattern.compile("\"fid\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                    if (m.find()) return m.group(1);
                }
            }
            fid = getCookieValue(client, "https://i.chaoxing.com", "fid");
            if (StringUtils.hasText(fid)) return fid;
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("获取 fid 失败：{}", e.getClass().getSimpleName());
        }
        return "";
    }

    private String fetchCpi(OkHttpClient client, String courseId, String classId) {
        try {
            String url = String.format(
                    "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&ut=s",
                    courseId, classId);
            Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    Matcher m = Pattern.compile("cpi=([0-9]+)").matcher(resp.body().string());
                    if (m.find()) return m.group(1);
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("解析 cpi 异常：{}", e.getClass().getSimpleName());
        }
        return "0";
    }

    private List<KnowledgePoint> fetchKnowledgePoints(OkHttpClient client, String courseId, String classId, String cpi) {
        List<KnowledgePoint> points = new ArrayList<>();
        try {
            String url = String.format(
                    "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&cpi=%s&ut=s",
                    courseId, classId, cpi);
            Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
            String html = executeStringWithCaptchaCheck(client, req, null, null);
            if (!StringUtils.hasText(html)) return points;
            Document doc = Jsoup.parse(html);
            Set<String> seen = new LinkedHashSet<>();
            for (Element li : doc.select("div.chapter_unit li")) {
                Element cur = li.selectFirst("div[id^=cur], [id^=cur]");
                if (cur == null) continue;
                Matcher idMatcher = Pattern.compile("^cur([0-9]+)$").matcher(cur.id());
                if (!idMatcher.find()) continue;
                String id = idMatcher.group(1);
                if (!seen.add(id)) continue;
                Element titleNode = li.selectFirst("a.clicktitle");
                String title = titleNode != null ? titleNode.text().trim() : li.text().trim();
                if (!StringUtils.hasText(title)) title = "章节 " + id;
                KnowledgePoint kp = new KnowledgePoint();
                kp.id = id;
                kp.title = title;
                Element jobCount = li.selectFirst("input.knowledgeJobCount");
                if (jobCount != null && StringUtils.hasText(jobCount.attr("value"))) {
                    try {
                        kp.jobCount = Integer.parseInt(jobCount.attr("value"));
                    } catch (Exception ignored) {
                        ExecutionScope.rethrowControl(ignored);
                    }
                }
                points.add(kp);
            }

            if (points.isEmpty()) {
                Pattern pat = Pattern.compile("id=\"cur([0-9]+)\"[^>]*>.*?<a[^>]*class=\"[^\"]*clicktitle[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);
                Matcher m = pat.matcher(html);
                while (m.find() && seen.add(m.group(1))) {
                    KnowledgePoint kp = new KnowledgePoint();
                    kp.id    = m.group(1);
                    kp.title = m.group(2).replaceAll("<[^>]+>", "").trim();
                    points.add(kp);
                }
            }

            if (points.isEmpty()) {
                Pattern fallback = Pattern.compile("id=\"node([0-9]+)\"[^>]*title=\"([^\"]+)\"");
                Matcher fm = fallback.matcher(html);
                while (fm.find() && seen.add(fm.group(1))) {
                    KnowledgePoint kp = new KnowledgePoint();
                    kp.id    = fm.group(1);
                    kp.title = fm.group(2).trim();
                    points.add(kp);
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("提取章节列表异常：", e);
        }
        return points;
    }

    private Integer fetchCoursePlatformProgress(OkHttpClient client, String courseId, String classId, String cpi) {
        try {
            String url = String.format(
                    "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&cpi=%s&ut=s",
                    courseId, classId, cpi);
            Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
            String html = executeStringWithCaptchaCheck(client, req, null, null);
            if (!StringUtils.hasText(html)) return null;
            Document doc = Jsoup.parse(html);
            Element head = doc.selectFirst(".chapter_head");
            String text = head != null ? head.text() : doc.text();
            Matcher matcher = Pattern.compile("已完成任务点\\D*(\\d+)\\s*/\\s*(\\d+)").matcher(text);
            if (matcher.find()) {
                int done = Integer.parseInt(matcher.group(1));
                int total = Integer.parseInt(matcher.group(2));
                if (total > 0) return Math.max(0, Math.min(100, (int) Math.round(done * 100.0 / total)));
            }
        } catch (Exception error) {
            ExecutionScope.rethrowControl(error);
            log.debug("读取超星课程平台进度失败：{}", error.getClass().getSimpleName());
        }
        return null;
    }

    private JobFetchResult fetchJobs(OkHttpClient client, CourseTask task,
                                     KnowledgePoint point, String cpi, String fid,
                                     TaskLogger taskLogger) {
        List<JobPoint> jobs = new ArrayList<>();
        JobInfo jobInfo = new JobInfo();
        boolean hasAnyCards = false;
        try {
            warmupKnowledgeCards(client, task, point, cpi);
            int miss = 0;
            for (int num = 0; num < 50; num++) {
                HttpUrl url = Objects.requireNonNull(
                                HttpUrl.parse("https://mooc1.chaoxing.com/mooc-ans/knowledge/cards")).newBuilder()
                        .addQueryParameter("clazzid",    task.getClassId())
                        .addQueryParameter("courseid",   task.getCourseId())
                        .addQueryParameter("knowledgeid", point.id)
                        .addQueryParameter("ut",         "s")
                        .addQueryParameter("cpi",        cpi)
                        .addQueryParameter("num",        String.valueOf(num))
                        .addQueryParameter("v",          "2025-0424-1038-4")
                        .addQueryParameter("mooc2",      "1")
                        .addQueryParameter("isMicroCourse", "false")
                        .addQueryParameter("editorPreview", "0")
                        .build();
                Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build();
                String html = executeStringWithCaptchaCheck(client, req, taskLogger, task.getId());
                if (!StringUtils.hasText(html)) {
                    if (++miss >= 2) break;
                    continue;
                }
                if (html.contains("章节未开放")) break;
                    JsonNode cardsData = CardPayloadParser.parse(html);
                    if (cardsData == null) {
                        if (++miss >= 2) break;
                        continue;
                    }
                    miss = 0;

                    JsonNode defaults = cardsData.path("defaults");
                    JobInfo currentInfo = new JobInfo();
                    currentInfo.cpi = cpi;
                    currentInfo.knowledgeid = point.id;
                    if (!defaults.isMissingNode()) {
                        currentInfo.ktoken      = defaults.path("ktoken").asText("");
                        currentInfo.mtEnc       = defaults.path("mtEnc").asText("");
                        currentInfo.defenc      = defaults.path("defenc").asText("");
                        currentInfo.cardid      = defaults.path("cardid").asText("");
                        currentInfo.cpi         = defaults.path("cpi").asText(cpi);
                        currentInfo.qnenc       = defaults.path("qnenc").asText("");
                        currentInfo.knowledgeid = defaults.path("knowledgeid").asText(point.id);
                        currentInfo.reportTimeInterval = defaults.path("reportTimeInterval").asInt(60);
                        jobInfo = currentInfo;
                    }
                    JsonNode attachments = cardsData.path("attachments");
                    if (!attachments.isArray() || attachments.size() == 0) {
                        if (++miss >= 2) break;
                        continue;
                    }
                    miss = 0;
                    hasAnyCards = true;

                    for (JsonNode card : attachments) {
                        String cardType  = card.path("type").asText("").toLowerCase();
                        JsonNode property = card.path("property");
                        boolean completed = isResourceCompleted(card, property);
                        String otherInfo = card.path("otherInfo").asText("");
                        if (otherInfo.contains("&")) otherInfo = otherInfo.split("&")[0];
                        boolean taskCard = card.path("job").asBoolean(false)
                                || StringUtils.hasText(card.path("jobid").asText(""))
                                || "read".equals(cardType);
                        if (!taskCard) continue;
                        String propType      = property.path("type").asText("").toLowerCase();
                        String resourceType  = property.path("resourceType").asText("").toLowerCase();
                        boolean isLive = cardType.contains("live") || propType.contains("live")
                                || resourceType.contains("live")
                                || !property.path("liveId").isMissingNode()
                                || !property.path("streamName").isMissingNode()
                                || !property.path("vdoid").isMissingNode();

                        if (isLive) {
                            taskLogger.log(task.getId(), "  │  ├─ 检测到直播任务，跳过。");
                            continue;
                        }
                        if ("read".equals(cardType)) {
                            JobPoint jp = new JobPoint();
                            applyDefaults(jp, currentInfo, point.id, cpi);
                            jp.completed = completed;
                            jp.type      = "read";
                            jp.jobId     = card.path("jobid").asText("");
                            jp.jtoken    = card.path("jtoken").asText("");
                            jp.otherInfo = otherInfo;
                            jp.name      = property.path("title").asText("阅读任务");
                            jp.mid       = card.path("mid").asText("");
                            jp.enc       = card.path("enc").asText("");
                            if (StringUtils.hasText(jp.jobId)) jobs.add(jp);
                            continue;
                        }

                        JobPoint jp = new JobPoint();
                        applyDefaults(jp, currentInfo, point.id, cpi);
                        jp.completed = completed;
                        jp.otherInfo = otherInfo;
                        jp.mid       = card.path("mid").asText("");
                        jp.enc       = card.path("enc").asText("");
                        jp.aid       = card.path("aid").asText("");

                        switch (cardType) {
                            case "video":
                            case "audio":
                                jp.type      = cardType;
                                jp.jobId     = card.path("jobid").asText("");
                                jp.objectId  = card.path("objectId").asText("");
                                jp.name      = property.path("name").asText("视频任务");
                                jp.playTime  = card.path("playTime").asInt(0);
                                jp.rt        = property.path("rt").asText("");
                                jp.attDuration        = card.path("attDuration").asText("");
                                jp.attDurationEnc     = card.path("attDurationEnc").asText("");
                                jp.videoFaceCaptureEnc = card.path("videoFaceCaptureEnc").asText("");
                                if (StringUtils.hasText(jp.jobId) && StringUtils.hasText(jp.objectId))
                                    jobs.add(jp);
                                break;

                            case "document":
                                jp.type      = "document";
                                jp.jobId     = card.path("jobid").asText("");
                                jp.jtoken    = card.path("jtoken").asText("");
                                jp.objectId  = property.path("objectid").asText("");
                                jp.name      = property.path("name").asText("文档任务");
                                if (StringUtils.hasText(jp.jobId)) jobs.add(jp);
                                break;

                            case "workid":
                                jp.type  = "workid";
                                jp.jobId = card.path("jobid").asText("");
                                jp.name  = property.path("name").asText(property.path("title").asText("作业任务"));
                                if (StringUtils.hasText(jp.jobId)) jobs.add(jp);
                                break;

                            default:
                                log.debug("未知任务类型：{}", cardType);
                        }
                    }

                sleepRandom(400, 700);
            }

        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            throw new TaskSignal("RETRY_WAIT", "CARD_PARSE_FAILED", "课程卡片解析失败，未标记为完成");
        }
        return new JobFetchResult(jobs, jobInfo, hasAnyCards);
    }

    private void warmupKnowledgeCards(OkHttpClient client, CourseTask task, KnowledgePoint point, String cpi) {
        try {
            HttpUrl url = Objects.requireNonNull(
                    HttpUrl.parse("https://mooc1.chaoxing.com/mooc-ans/mycourse/studentstudyAjax")).newBuilder()
                .addQueryParameter("courseId", task.getCourseId())
                .addQueryParameter("clazzid", task.getClassId())
                .addQueryParameter("chapterId", point.id)
                .addQueryParameter("cpi", cpi)
                .addQueryParameter("verificationcode", "")
                .addQueryParameter("mooc2", "1")
                .addQueryParameter("microTopicId", "0")
                .addQueryParameter("editorPreview", "0")
                .build();
            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards")
                .get()
                .build();
            try (Response ignored = client.newCall(request).execute()) {
            }
            sleepRandom(300, 600);
        } catch (Exception error) {
            ExecutionScope.rethrowControl(error);
            log.debug("章节卡片预热失败：{}", error.getClass().getSimpleName());
        }
    }

    private void applyDefaults(JobPoint job, JobInfo info, String pointId, String cpi) {
        job.knowledgeId = StringUtils.hasText(info.knowledgeid) ? info.knowledgeid : pointId;
        job.ktoken = info.ktoken;
        job.mtEnc = info.mtEnc;
        job.defenc = info.defenc;
        job.cardid = info.cardid;
        job.cpi = StringUtils.hasText(info.cpi) ? info.cpi : cpi;
        job.qnenc = info.qnenc;
        job.reportTimeInterval = info.reportTimeInterval;
    }

    private WorkQuestions parseWorkHtml(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element form = doc.selectFirst("form");
            if (form == null) return null;
            CxSecretDecoder decoder = CxSecretDecoder.fromHtml(html);

            Map<String, String> formData = new LinkedHashMap<>();
            for (Element input : form.select("input")) {
                String name = input.attr("name");
                if (!StringUtils.hasText(name) || name.contains("answer")) continue;
                formData.put(name, input.attr("value"));
            }

            List<WorkQuestion> questions = new ArrayList<>();
            for (Element qDiv : form.select("div.singleQuesId")) {
                WorkQuestion q = new WorkQuestion();
                q.id = qDiv.attr("data");

                Element timu = qDiv.selectFirst("div.TiMu");
                if (timu == null) continue;
                String typeCode = timu.attr("data");
                q.type = resolveQuestionType(typeCode);
                Element titleDiv = qDiv.selectFirst("div.Zy_TItle");
                var title = decoder.decode(titleDiv);
                q.title = title.text();
                q.decoded = title.complete() && StringUtils.hasText(q.title);

                Elements liItems = qDiv.select("ul li");
                StringBuilder optSb = new StringBuilder();
                for (Element li : liItems) {
                    var option = decoder.decode(li);
                    q.decoded &= option.complete();
                    optSb.append(option.text()).append("\n");
                }
                q.options = new ArrayList<>(Arrays.asList(optSb.toString().trim().split("\n")));
                q.options.removeIf(s -> !StringUtils.hasText(s));

                Element answerTypeInput = qDiv.selectFirst("input[name^=answertype]");
                String answertypeKey = "answertype" + q.id;
                String answertypeVal = (answerTypeInput != null) ? answerTypeInput.attr("value") : "";
                q.rawAnswerType = answertypeVal;
                formData.put(answertypeKey, answertypeVal);

                Element tkSizeInput = qDiv.selectFirst("input[name=tiankongsize" + q.id + "]");
                if (tkSizeInput != null && StringUtils.hasText(tkSizeInput.attr("value"))) {
                    try {
                        q.completionCount = Integer.parseInt(tkSizeInput.attr("value"));
                    } catch (Exception ignored) {
            ExecutionScope.rethrowControl(ignored);}
                } else {
                    int editors = qDiv.select("textarea, div.mce-edit-area, input.tiankong").size();
                    if (editors > 0) q.completionCount = editors;
                }

                if (!"2".equals(answertypeVal)) {
                    formData.put("answer" + q.id, "");
                }

                questions.add(q);
            }

            if (!questions.isEmpty()) {
                StringBuilder answerwqbid = new StringBuilder();
                for (WorkQuestion q : questions) {
                    answerwqbid.append(q.id).append(",");
                }
                formData.put("answerwqbid", answerwqbid.toString());
            }

            WorkQuestions result = new WorkQuestions();
            result.formData  = formData;
            result.questions = questions;
            return result;
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("解析作业 HTML 异常：", e);
            return null;
        }
    }

    private boolean isResourceCompleted(JsonNode card, JsonNode property) {
        String cardType = card.path("type").asText("").toLowerCase(Locale.ROOT);
        boolean platformSaysNoPendingJob = Set.of("document", "workid", "read").contains(cardType)
            && !card.path("job").asBoolean(false);
        return truthy(card, "isPassed", "passed", "complete", "completed", "finished", "isFinished", "jobFinished", "finishJob", "done")
            || truthy(property, "isPassed", "passed", "complete", "completed", "finished", "isFinished", "jobFinished", "finishJob", "done", "read")
            || platformSaysNoPendingJob
            || "finished".equalsIgnoreCase(card.path("jobStatus").asText(""))
            || "finished".equalsIgnoreCase(property.path("jobStatus").asText(""));
    }

    private boolean truthy(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) continue;
            if (value.isBoolean() && value.asBoolean()) return true;
            if (value.isNumber() && value.asInt() > 0) return true;
            String text = value.asText("").trim();
            if (Set.of("1", "true", "yes", "y", "done", "finish", "finished", "complete", "completed", "passed", "success").contains(text.toLowerCase())) return true;
        }
        return false;
    }

    private String resolveQuestionType(String code) {
        switch (code) {
            case "0": return "single";
            case "1": return "multiple";
            case "2": return "completion";
            case "3": return "judgement";
            case "4": return "subjective";
            default:  return "other";
        }
    }

    private OkHttpClient buildClient() {
        return OkHttpUtil.createClientBuilder()
                .cookieJar(OkHttpUtil.createInMemoryCookieJar())
                .build();
    }

    private String aesEncrypt(String data, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new IvParameterSpec(keyBytes));
        return Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private String getCookieValue(OkHttpClient client, String domainUrl, String cookieName) {
        HttpUrl httpUrl = HttpUrl.parse(domainUrl);
        if (httpUrl == null || client.cookieJar() == null) return "";
        for (Cookie c : client.cookieJar().loadForRequest(httpUrl)) {
            if (c.name().equalsIgnoreCase(cookieName)) return c.value();
        }
        return "";
    }

    private String extractUrlParam(String url, String param) {
        if (!StringUtils.hasText(url)) return "";
        int start = url.indexOf(param + "=");
        if (start < 0) return "";
        start += param.length() + 1;
        int end = url.indexOf("&", start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    private String extractNodeId(String otherInfo) {
        if (!StringUtils.hasText(otherInfo)) return "";
        Matcher m = Pattern.compile("nodeId_(.*?)-").matcher(otherInfo);
        return m.find() ? m.group(1) : "";
    }

    private void sleepRandom(int minMs, int maxMs) {
        ExecutionScope.sleep(minMs + random.nextInt(Math.max(1, maxMs - minMs)));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    private boolean checkNeedCaptcha(String html) {
        if (!StringUtils.hasText(html)) return false;
        return html.contains("processVerify.ac") || html.contains("【9010】") || html.contains("操作异常，请输入图片中的验证码");
    }

    private String executeStringWithCaptchaCheck(OkHttpClient client, Request request, TaskLogger logger, Long taskId) throws Exception {
        generalRateLimiter.limitRate(false, 0, 0);
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403)
                throw new TaskSignal("WAITING_AUTH", "PLATFORM_AUTH_EXPIRED", "平台需要重新认证");
            if (!response.isSuccessful() || response.body() == null)
                throw new TaskSignal("RETRY_WAIT", "PLATFORM_REQUEST_FAILED", "平台请求失败");
            String body = response.body().string();
            if (checkNeedCaptcha(body)) throw new TaskSignal("WAITING_AUTH", "PLATFORM_VERIFICATION_REQUIRED", "请到平台完成验证后重新认证");
            return body;
        }
    }

    private boolean handleCaptchaPass(OkHttpClient client, TaskLogger logger, Long taskId) {
        throw new TaskSignal("WAITING_AUTH", "PLATFORM_VERIFICATION_REQUIRED", "请到平台完成验证后重新认证");
    }

    private static class KnowledgePoint {
        String id;
        String title;
        int jobCount = 0;
    }

    private static class JobPoint {
        String jobId   = "";
        String objectId = "";
        String otherInfo = "";
        String name    = "";
        String type    = "";
        String knowledgeId = "";
        String ktoken  = "";
        String mtEnc   = "";
        String defenc  = "";
        String cardid  = "";
        String cpi     = "";
        String qnenc   = "";
        String jtoken  = "";
        String mid     = "";
        String enc     = "";
        String aid     = "";
        String rt      = "";
        String attDuration = "";
        String attDurationEnc = "";
        String videoFaceCaptureEnc = "";
        boolean completed;
        int    playTime = 0;
        int    reportTimeInterval = 60;
    }

    private static class JobInfo {
        String ktoken      = "";
        String mtEnc       = "";
        String defenc      = "";
        String cardid      = "";
        String cpi         = "";
        String qnenc       = "";
        String knowledgeid = "";
        int    reportTimeInterval = 60;
    }

    private static class JobFetchResult {
        final List<JobPoint> jobs;
        final JobInfo        jobInfo;
        final boolean        hasAnyCards;
        JobFetchResult(List<JobPoint> jobs, JobInfo jobInfo, boolean hasAnyCards) {
            this.jobs        = jobs;
            this.jobInfo     = jobInfo;
            this.hasAnyCards = hasAnyCards;
        }
    }

    private static class VideoLogResult {
        final boolean isPassed;
        final int     statusCode;
        VideoLogResult(boolean isPassed, int statusCode) {
            this.isPassed   = isPassed;
            this.statusCode = statusCode;
        }
    }

    private static class WorkQuestion {
        String id;
        String type;
        String rawAnswerType;
        String title;
        List<String> options;
        int completionCount = 1;
        boolean decoded;
    }

    private static class WorkQuestions {
        Map<String, String>  formData;
        List<WorkQuestion>   questions;
    }

    private record SubmitResult(boolean success, String message) {}

    private static class RateLimiter {
        private final long callIntervalMs;
        private long lastCallTime;
        private final Object lock = new Object();

        public RateLimiter(long callIntervalMs) {
            this.callIntervalMs = callIntervalMs;
            this.lastCallTime = System.currentTimeMillis();
        }

        public void limitRate(boolean randomTime, long randomMinMs, long randomMaxMs) {
            long waitTime = 0;
            synchronized (lock) {
                long now = System.currentTimeMillis();
                long baseWait = Math.max(lastCallTime + callIntervalMs - now, 0);
                long extraWait = 0;
                if (randomTime && randomMaxMs > randomMinMs) {
                    extraWait = randomMinMs + (long) (Math.random() * (randomMaxMs - randomMinMs));
                }
                waitTime = baseWait + extraWait;
                lastCallTime = now + waitTime;
            }
            if (waitTime > 0) {
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
