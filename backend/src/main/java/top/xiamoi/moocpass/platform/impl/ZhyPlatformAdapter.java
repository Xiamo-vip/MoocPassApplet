package top.xiamoi.moocpass.platform.impl;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
import top.xiamoi.moocpass.platform.CourseResource;
import top.xiamoi.moocpass.task.ExecutionScope;
import top.xiamoi.moocpass.task.TaskSignal;
import top.xiamoi.moocpass.infrastructure.Crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ZhyPlatformAdapter implements MoocPlatformAdapter {

    private static final String PLATFORM_CODE = "zhy";
    private static final String PLATFORM_NAME = "职教云";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36";
    private static final String AES_KEY = "learnspaceaes123";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CourseTaskMapper courseTaskMapper;

    @Autowired
    private TaskLogger logger;

    @Override
    public List<CourseResource> getResources(UserPlatformConfig config, CourseVO course) {
        CourseTask task = CourseTask.builder().courseId(course.getCourseId()).classId(course.getClassId())
            .courseName(course.getName()).speed(1.0).inspection(true).build();
        executeCourseTask(task, config, null, null, logger);
        if ("FAILED".equals(task.getStatus())) throw new TaskSignal("RETRY_WAIT", "COURSE_SYNC_FAILED", "职教云章节同步失败");
        return task.getResources().stream().collect(java.util.stream.Collectors.toMap(CourseResource::id, r -> r,
            (first, second) -> second, LinkedHashMap::new)).values().stream().toList();
    }

    private String resourceType(String raw) {
        return switch (raw.toLowerCase()) {
            case "video", "视频", "courseware" -> "video";
            case "audio", "音频" -> "audio";
            case "document", "文档", "pdf", "ppt", "图片", "文本" -> "document";
            default -> "unsupported";
        };
    }

    @Override
    public String getPlatformCode() {
        return PLATFORM_CODE;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM_NAME;
    }

    @Override
    public boolean validateAccount(String username, String password) {
        return false;
    }

    @Override
    public boolean validateAuth(String token) {
        if (!StringUtils.hasText(token)) return false;
        String cleanToken = token.trim();
        try {
            OkHttpClient client = buildClient(cleanToken);
            String url = "https://mooc.icve.com.cn/patch/zhzj/api_getUserInfo.action";
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                    .build();
            String body = executeStringWithRetry(client, req, null, null, "验证 Token");
            if (StringUtils.hasText(body) && body.startsWith("{")) {
                JsonNode json = objectMapper.readTree(body);
                return "A0000".equals(json.path("errorCode").asText(""))
                        && !json.path("data").isMissingNode();
            }
        } catch (Exception ignored) {
            ExecutionScope.rethrowControl(ignored);}
        return false;
    }

    @Override
    public String accountIdentity(String username, String token) {
        if (!StringUtils.hasText(token)) return null;
        try {
            OkHttpClient client = buildClient(token.trim());
            Request request = new Request.Builder()
                .url("https://mooc.icve.com.cn/patch/zhzj/api_getUserInfo.action")
                .header("User-Agent", USER_AGENT)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();
            String body = executeStringWithRetry(client, request, null, null, "验证账号身份");
            JsonNode root = objectMapper.readTree(body);
            if (!"A0000".equals(root.path("errorCode").asText(""))) return null;
            JsonNode data = root.path("data");
            for (JsonNode candidate : List.of(data, data.path("userInfo"), data.path("user"))) {
                for (String field : List.of("userId", "userID", "userid", "user_id", "studentId", "student_id", "id")) {
                    String value = candidate.path(field).asText("").trim();
                    if (!value.isBlank()) return value;
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("职教云账号身份获取失败: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    @Override
    public List<CourseVO> getCourseList(UserPlatformConfig platformConfig) {
        List<CourseVO> list = new ArrayList<>();
        if (platformConfig == null || !StringUtils.hasText(platformConfig.getToken())) {
            return list;
        }
        String token = platformConfig.getToken().trim();
        Set<String> seenCourseIds = new HashSet<>();
        try {
            OkHttpClient client = buildClient(token);

            List<CourseVO> aiCourses = executeAiMoocCourseList(client, token);
            if (aiCourses != null && !aiCourses.isEmpty()) {
                for (CourseVO c : aiCourses) {
                    if (StringUtils.hasText(c.getCourseId()) && seenCourseIds.add(c.getCourseId())) {
                        list.add(c);
                    }
                }
            }

            List<CourseVO> zykCourses = executeZykCourseList(client, token);
            if (zykCourses != null && !zykCourses.isEmpty()) {
                for (CourseVO c : zykCourses) {
                    if (StringUtils.hasText(c.getCourseId()) && seenCourseIds.add(c.getCourseId())) {
                        list.add(c);
                    }
                }
            }

            loadMooc(client, token);
            int pageNumber = 1;
            int totalPages = 1;
            while (pageNumber <= totalPages) {
                JsonNode root = executeSelectMoocCourse(client, token, pageNumber, 6, "1");
                if (root == null) break;
                JsonNode dataNode = root.path("data");
                if (dataNode.isArray() && dataNode.size() > 0) {
                    for (JsonNode item : dataNode) {
                        if (item.isArray() && item.size() > 6) {
                            String name     = item.get(0).asText("");
                            String teacher  = item.size() > 1  ? item.get(1).asText("") : "";
                            String courseId = item.get(6).asText("");
                            String coverUrl = item.size() > 15 ? item.get(15).asText("") : "";
                            if (StringUtils.hasText(courseId) && seenCourseIds.add(courseId)) {
                                list.add(CourseVO.builder()
                                        .courseId(courseId)
                                        .classId("")
                                        .name(name)
                                        .teacher(teacher)
                                        .coverUrl(coverUrl)
                                        .build());
                            }
                        }
                    }
                }

                JsonNode totalPageNode = root.path("totalPage");
                if (!totalPageNode.isMissingNode()) {
                    totalPages = totalPageNode.asInt(1);
                }
                pageNumber++;
                if (pageNumber <= totalPages) {
                    ExecutionScope.sleep(1500);
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.error("获取职教云课程列表失败:", e);
        }
        return list;
    }

    private List<CourseVO> executeAiMoocCourseList(OkHttpClient client, String token) {
        List<CourseVO> resList = new ArrayList<>();
        try {
            String passUrl = "https://ai.icve.com.cn/prod-api/auth/passLogin?token=" + token;
            Request reqPass = new Request.Builder()
                    .url(passUrl)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();
            String accessToken = null;
            String bodyStr = executeStringWithRetry(client, reqPass, null, null, "AI优课 passLogin");
            if (StringUtils.hasText(bodyStr) && bodyStr.startsWith("{")) {
                JsonNode json = objectMapper.readTree(bodyStr);
                accessToken = json.path("access_token").asText(null);
                if (accessToken == null && json.has("data")) {
                    accessToken = json.path("data").path("access_token").asText(null);
                }
            }

            if (!StringUtils.hasText(accessToken)) {
                log.debug("AI 优课 passLogin 未获取到 access_token，跳过此 API 源");
                return resList;
            }

            HttpUrl courseUrl = Objects.requireNonNull(
                            HttpUrl.parse("https://ai.icve.com.cn/prod-api/course/courseInfo/myCourse"))
                    .newBuilder()
                    .addQueryParameter("pageNum", "1")
                    .addQueryParameter("pageSize", "9999")
                    .addQueryParameter("queryStatus", "1")
                    .build();
            Request reqCourse = new Request.Builder()
                    .url(courseUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();
            String courseBodyStr = executeStringWithRetry(client, reqCourse, null, null, "AI优课 myCourse");
            if (StringUtils.hasText(courseBodyStr) && courseBodyStr.startsWith("{")) {
                JsonNode json = objectMapper.readTree(courseBodyStr);
                JsonNode rows = json.path("rows");
                if (rows.isArray()) {
                    for (JsonNode row : rows) {
                        String courseName = row.path("courseName").asText("");
                        String teacher = row.path("displayName").asText("");
                        if (!StringUtils.hasText(teacher)) {
                            teacher = row.path("presidingTeacher").asText("");
                        }
                        String courseId = row.path("courseId").asText("");
                        if (!StringUtils.hasText(courseId)) {
                            courseId = row.path("id").asText("");
                        }
                        String courseInfoId = row.path("id").asText("");
                        if (!StringUtils.hasText(courseInfoId)) {
                            courseInfoId = row.path("courseInfoId").asText("");
                        }
                        String coverUrl = row.path("imageUrl").asText("");
                        String courseInfoName = row.path("courseInfoName").asText("");
                        String displayName = courseName;
                        if (StringUtils.hasText(courseInfoName) && !courseInfoName.equals(courseName)) {
                            displayName = courseName + " (" + courseInfoName + ")";
                        }
                        resList.add(CourseVO.builder()
                                .courseId(courseId)
                                .classId(courseInfoId)
                                .name(displayName)
                                .teacher(teacher)
                                .coverUrl(coverUrl)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("获取 AI 优课课程列表异常: {}", e.getClass().getSimpleName());
        }
        return resList;
    }

    private boolean executeAiMoocCourseTask(OkHttpClient client, String token, CourseTask task, TaskLogger taskLogger) {
        String passUrl = "https://ai.icve.com.cn/prod-api/auth/passLogin?token=" + token;
        Request reqPass = new Request.Builder().url(passUrl).header("User-Agent", USER_AGENT).get().build();
        String accessToken = null;
        try {
            String bodyStr = executeStringWithRetry(client, reqPass, taskLogger, task.getId(), "AI优课 鉴权");
            if (StringUtils.hasText(bodyStr) && bodyStr.startsWith("{")) {
                JsonNode json = objectMapper.readTree(bodyStr);
                accessToken = json.path("access_token").asText(null);
                if (accessToken == null && json.has("data")) {
                    accessToken = json.path("data").path("access_token").asText(null);
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("passLogin Exception: {}", e.getClass().getSimpleName());
        }

        if (!StringUtils.hasText(accessToken)) {
            return false;
        }

        String rawCourseId = task.getCourseId();
        String rawClassId = task.getClassId();

        List<String[]> candidatePairs = new ArrayList<>();
        if (StringUtils.hasText(rawClassId)) {
            candidatePairs.add(new String[]{rawClassId, rawCourseId});
        }
        candidatePairs.add(new String[]{rawCourseId, rawCourseId});
        if (StringUtils.hasText(rawClassId)) {
            candidatePairs.add(new String[]{rawCourseId, rawClassId});
        }

        JsonNode designArray = null;
        String finalCourseInfoId = null;
        String finalCourseId = null;

        for (String[] pair : candidatePairs) {
            String cInfoId = pair[0];
            String cId = pair[1];
            HttpUrl designUrl = Objects.requireNonNull(HttpUrl.parse("https://ai.icve.com.cn/prod-api/course/courseDesign/getStudentDesignList"))
                    .newBuilder()
                    .addQueryParameter("courseInfoId", cInfoId)
                    .addQueryParameter("courseId", cId)
                    .build();
            Request reqDesign = new Request.Builder()
                    .url(designUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            try {
                String bodyStr = executeStringWithRetry(client, reqDesign, taskLogger, task.getId(), "获取课程设计大纲");
                if (StringUtils.hasText(bodyStr)) {
                    JsonNode parsed = null;
                    if (bodyStr.startsWith("[")) {
                        parsed = objectMapper.readTree(bodyStr);
                    } else if (bodyStr.startsWith("{")) {
                        JsonNode json = objectMapper.readTree(bodyStr);
                        if (json.has("data") && json.path("data").isArray()) {
                            parsed = json.path("data");
                        }
                    }
                    if (parsed != null && parsed.isArray() && parsed.size() > 0) {
                        designArray = parsed;
                        finalCourseInfoId = cInfoId;
                        finalCourseId = cId;
                        break;
                    }
                }
            } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
                log.warn("getStudentDesignList candidate pair Exception: {}", e.getClass().getSimpleName());
            }
        }

        if (designArray == null || !designArray.isArray() || designArray.size() == 0) {
            return false;
        }

        taskLogger.log(task.getId(), "已成功识别并接入 AI 优课 (AIMooc) 原生服务引擎，正在查询学习记录...");

        Set<String> completedRecords = new HashSet<>();
        HttpUrl recordListUrl = Objects.requireNonNull(HttpUrl.parse("https://ai.icve.com.cn/prod-api/course/studyRecord/completed/courseware"))
                .newBuilder()
                .addQueryParameter("courseInfoId", finalCourseInfoId)
                .addQueryParameter("courseId", finalCourseId)
                .build();
        Request reqRecords = new Request.Builder()
                .url(recordListUrl)
                .header("User-Agent", USER_AGENT)
                .header("Authorization", "Bearer " + accessToken)
                .get()
                .build();
        try {
            String bodyStr = executeStringWithRetry(client, reqRecords, taskLogger, task.getId(), "查询已完成记录");
            if (StringUtils.hasText(bodyStr)) {
                JsonNode jsonNode = objectMapper.readTree(bodyStr);
                JsonNode dataNode = jsonNode.has("data") ? jsonNode.path("data") : jsonNode;
                if (dataNode.isArray()) {
                    for (JsonNode item : dataNode) {
                        if (item.isTextual()) {
                            completedRecords.add(item.asText());
                        } else if (item.has("id")) {
                            completedRecords.add(item.path("id").asText());
                        } else if (item.has("sourceId")) {
                            completedRecords.add(item.path("sourceId").asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("getCompletedRecords Exception: {}", e.getClass().getSimpleName());
        }

        taskLogger.log(task.getId(), String.format("读取已完成记录 %d 条，开始解析大章列表 (%d 章)...", completedRecords.size(), designArray.size()));

        int totalChapters = designArray.size();
        for (int i = 0; i < totalChapters; i++) {
            if (Thread.currentThread().isInterrupted()) {
                taskLogger.log(task.getId(), "任务已被暂停/停止。");
                return true;
            }
            JsonNode chapterNode = designArray.get(i);
            String chapName = chapterNode.path("name").asText("未知章节");
            String parentId = chapterNode.path("id").asText("");
            taskLogger.log(task.getId(), String.format("[%d/%d] 正在处理章节: %s", i + 1, totalChapters, chapName));

            HttpUrl cellUrl = Objects.requireNonNull(HttpUrl.parse("https://ai.icve.com.cn/prod-api/course/courseDesign/getCellList"))
                    .newBuilder()
                    .addQueryParameter("courseInfoId", finalCourseInfoId)
                    .addQueryParameter("courseId", finalCourseId)
                    .addQueryParameter("parentId", parentId)
                    .build();
            Request reqCells = new Request.Builder()
                    .url(cellUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            try {
                String bodyStr = executeStringWithRetry(client, reqCells, taskLogger, task.getId(), "获取小节节点");
                if (StringUtils.hasText(bodyStr)) {
                    JsonNode cellNodes = null;
                    if (bodyStr.startsWith("[")) {
                        cellNodes = objectMapper.readTree(bodyStr);
                    } else if (bodyStr.startsWith("{")) {
                        JsonNode jsonNode = objectMapper.readTree(bodyStr);
                        cellNodes = jsonNode.has("data") ? jsonNode.path("data") : jsonNode;
                    }
                    if (cellNodes != null && cellNodes.isArray()) {
                        processAiMoocNodes(client, accessToken, cellNodes, parentId, finalCourseId, finalCourseInfoId, completedRecords, task, taskLogger, 1);
                    }
                }
            } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
                log.warn("getCellList Exception: {}", e.getClass().getSimpleName());
            }

            int currentProgress = Math.min(99, (int) (((double) (i + 1) / totalChapters) * 100));
            saveProgress(task, currentProgress, String.format("[%d/%d] %s", i + 1, totalChapters, chapName));
        }

        saveProgress(task, 99, "正在核验平台进度");
        task.setStatus("VERIFYING");
        taskLogger.log(task.getId(), "课程 [" + task.getCourseName() + "] 资源处理结束，正在核验平台进度");
        return true;
    }

    private void processAiMoocNodes(OkHttpClient client, String accessToken, JsonNode nodes, String parentId, String courseId, String courseInfoId, Set<String> completedRecords, CourseTask task, TaskLogger taskLogger, int depth) throws Exception {
        for (JsonNode node : nodes) {
            if (Thread.currentThread().isInterrupted()) return;
            String name = node.path("name").asText("未知资源");
            String fileType = node.path("fileType").asText("未知类型");
            JsonNode children = node.path("children");

            StringBuilder indent = new StringBuilder();
            for (int k = 0; k < depth; k++) indent.append("    ");

            if (children != null && children.isArray() && children.size() > 0) {
                taskLogger.log(task.getId(), indent + "└─ " + name);
                processAiMoocNodes(client, accessToken, children, parentId, courseId, courseInfoId, completedRecords, task, taskLogger, depth + 1);
            } else {
                if ("作业".equals(fileType) || "考试".equals(fileType) || "测验".equals(fileType) || "子节点".equals(fileType) || "文件夹".equals(fileType)) {
                    taskLogger.log(task.getId(), indent + "[跳过" + fileType + "] " + name);
                    continue;
                }
                String sourceId = node.path("id").asText("");
                String resourceId = "ai/" + sourceId;
                String type = resourceType(fileType);
                task.getResources().add(new CourseResource(resourceId, parentId, name, type, completedRecords.contains(sourceId)));
                if (task.isInspection()) continue;
                if (!ExecutionScope.current().includes(resourceId, parentId, type)) continue;
                if (!completedRecords.contains(sourceId)) ExecutionScope.current().begin(resourceId);
                if (completedRecords.contains(sourceId)) {
                    taskLogger.log(task.getId(), indent + "[已完成] " + name);
                    continue;
                }

                int totalNum = 1;
                String courseContentStr = node.path("fileUrl").asText("");
                if (StringUtils.hasText(courseContentStr)) {
                    try {
                        JsonNode ccNode = objectMapper.readTree(courseContentStr);
                        if (ccNode.has("duration")) {
                            String durationStr = ccNode.path("duration").asText("");
                            int sec = parseTimeToSeconds(durationStr);
                            if (sec > 0) totalNum = sec;
                        } else if (ccNode.has("page_count")) {
                            int pageCount = ccNode.path("page_count").asInt(0);
                            if (pageCount > 0) totalNum = pageCount;
                        }
                    } catch (Exception ignored) {
            ExecutionScope.rethrowControl(ignored);}
                }

                double speed = (task.getSpeed() != null && task.getSpeed() > 0) ? task.getSpeed() : 1.0;
                long waitMs = "video".equals(type) || "audio".equals(type) ? (long) (totalNum * 1000 / speed) : 1500;
                if (waitMs > 14_400_000) throw new TaskSignal("WAITING_USER", "DURATION_INVALID", "资源时长超出支持范围");
                taskLogger.log(task.getId(), String.format("%s[处理中] %s (%s, 学习数:%d)", indent, name, fileType, totalNum));
                ExecutionScope.sleep(waitMs);

                Map<String, Object> reqBody = new LinkedHashMap<>();
                reqBody.put("courseId", courseId);
                reqBody.put("courseInfoId", courseInfoId);
                reqBody.put("parentId", parentId);
                reqBody.put("sourceId", sourceId);
                reqBody.put("actualNum", totalNum);
                reqBody.put("lastNum", totalNum);
                reqBody.put("totalNum", totalNum);
                reqBody.put("studyDuration", totalNum);

                String jsonStr = objectMapper.writeValueAsString(reqBody);
                Request recordReq = new Request.Builder()
                        .url("https://ai.icve.com.cn/prod-api/course/studyRecord")
                        .header("User-Agent", USER_AGENT)
                        .header("Authorization", "Bearer " + accessToken)
                        .post(RequestBody.create(jsonStr, MediaType.parse("application/json; charset=utf-8")))
                        .build();

                try {
                    ExecutionScope.current().checkpoint("SUBMITTING");
                    String bodyStr = executeStringWithRetry(client, recordReq, taskLogger, task.getId(), "提交打卡记录");
                    if (StringUtils.hasText(bodyStr)) {
                        JsonNode resJson = objectMapper.readTree(bodyStr);
                        String msg = resJson.path("msg").asText("操作完成");
                        taskLogger.log(task.getId(), indent + "API 响应: " + msg);
                        int code = resJson.path("code").asInt(-1);
                        if (code != 0 && code != 200) throw new TaskSignal("WAITING_USER", "PROGRESS_REJECTED", "平台未确认资源学习记录");
                        ExecutionScope.current().checkpoint("ACKNOWLEDGED");
                        completedRecords.add(sourceId);
                    }
                } catch (Exception ex) {
            ExecutionScope.rethrowControl(ex);
                    taskLogger.log(task.getId(), indent + "记录上报重试失败: " + ex.getClass().getSimpleName());
                }
            }
        }
    }

    private List<CourseVO> executeZykCourseList(OkHttpClient client, String token) {
        List<CourseVO> resList = new ArrayList<>();
        try {
            String passUrl = "https://zyk.icve.com.cn/prod-api/auth/passLogin?token=" + token;
            Request reqPass = new Request.Builder()
                    .url(passUrl)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();
            String accessToken = null;
            String bodyStr = executeStringWithRetry(client, reqPass, null, null, "ZYK passLogin");
            if (StringUtils.hasText(bodyStr) && bodyStr.startsWith("{")) {
                JsonNode json = objectMapper.readTree(bodyStr);
                accessToken = json.path("access_token").asText(null);
                if (accessToken == null && json.has("data")) {
                    accessToken = json.path("data").path("access_token").asText(null);
                }
            }

            if (!StringUtils.hasText(accessToken)) {
                return resList;
            }

            String courseUrl = "https://zyk.icve.com.cn/prod-api/teacher/courseList/myCourseList?pageNum=1&pageSize=500&flag=1";
            Request reqCourse = new Request.Builder()
                    .url(courseUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();
            String courseBodyStr = executeStringWithRetry(client, reqCourse, null, null, "ZYK myCourseList");
            if (StringUtils.hasText(courseBodyStr) && courseBodyStr.startsWith("{")) {
                JsonNode json = objectMapper.readTree(courseBodyStr);
                JsonNode rows = json.path("rows");
                if (rows.isArray()) {
                    for (JsonNode row : rows) {
                        String courseName = row.path("courseName").asText("");
                        String teacher = row.path("presidingTeacher").asText("");
                        String courseId = row.path("courseInfoId").asText("");
                        if (!StringUtils.hasText(courseId)) {
                            courseId = row.path("courseId").asText("");
                        }
                        String coverUrl = row.path("thumbnail").asText("");
                        resList.add(CourseVO.builder()
                                .courseId(courseId)
                                .classId("")
                                .name(courseName)
                                .teacher(teacher)
                                .coverUrl(coverUrl)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("获取 ZYK 课程列表异常: {}", e.getClass().getSimpleName());
        }
        return resList;
    }

    private JsonNode executeSelectMoocCourse(OkHttpClient client, String token, int page, int pageSize, String selectType) {
        String baseUrl = "https://mooc.icve.com.cn/patch/zhzj/studentMooc_selectMoocCourse.action";
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(baseUrl)).newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("siteCode", "zhzj")
                .addQueryParameter("curPage", String.valueOf(page))
                .addQueryParameter("pageSize", String.valueOf(pageSize))
                .addQueryParameter("selectType", selectType)
                .build();

        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                    .build();
            String str = executeStringWithRetry(client, req, null, null, "selectMoocCourse");
            if (StringUtils.hasText(str) && str.startsWith("{")) {
                return objectMapper.readTree(str);
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("executeSelectMoocCourse 异常: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    private void loadMooc(OkHttpClient client, String token) {
        try {
            Request req = new Request.Builder()
                    .url("https://mooc.icve.com.cn/")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();
            executeStringWithRetry(client, req, null, null, "loadMooc");
        } catch (Exception ignored) {
            ExecutionScope.rethrowControl(ignored);}
    }

    @Override
    public void executeCourseTask(CourseTask task, UserPlatformConfig platformConfig,
                                  UserQuestionConfig questionConfig, QuestionSolver questionSolver,
                                  TaskLogger taskLogger) {
        taskLogger.log(task.getId(), "正在启动职教云 Java 原生挂机引擎...");
        String token = platformConfig != null ? platformConfig.getToken() : null;
        if (!StringUtils.hasText(token)) {
            taskLogger.log(task.getId(), "[错误] 缺失有效的 Token 凭证，任务终止。");
            task.setStatus("FAILED");
            return;
        }

        int taskOuterRetries = 1;
        for (int outerAttempt = 1; outerAttempt <= taskOuterRetries; outerAttempt++) {
            try {
                OkHttpClient client = buildClient(token);

                String courseId = task.getCourseId();
                taskLogger.log(task.getId(), "正在连接职教云服务器，初始化课程: " + task.getCourseName());

                if (executeAiMoocCourseTask(client, token, task, taskLogger)) {
                    return;
                }

                loadMooc(client, token);
                if (!task.isInspection()) signLearn(client, courseId, taskLogger, task.getId());
                taskLogger.log(task.getId(), "签到激活成功，准备提取传统章节结构...");

                String coursewareHtml = getCoursewareIndex(client, courseId, taskLogger, task.getId());
                if (!StringUtils.hasText(coursewareHtml)) {
                    taskLogger.log(task.getId(), "[警告] 解析课程结构失败，可能课程尚未开放。");
                    throw new TaskSignal("WAITING_USER", "COURSE_NOT_OPEN", "课程章节未开放或暂时无法解析");
                }

                Document doc = Jsoup.parse(coursewareHtml);
                Elements chapters    = doc.select("#learnMenu > div.s_chapter");
                Elements sectionLists = doc.select("#learnMenu > div.s_sectionlist");

                int totalChapters = chapters.size();
                taskLogger.log(task.getId(), String.format("成功解析课程架构，共包含 %d 个大章。", totalChapters));

                for (int i = 0; i < totalChapters; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        taskLogger.log(task.getId(), "任务已被暂停/停止。");
                        return;
                    }

                    Element chapElem  = chapters.get(i);
                    String chapTitle  = chapElem.attr("title");
                    taskLogger.log(task.getId(), String.format("[%d/%d] 正在处理章节: %s", i + 1, totalChapters, chapTitle));

                    if (i >= sectionLists.size()) continue;
                    Element secListElem  = sectionLists.get(i);
                    Elements sections    = secListElem.select("> div.s_section");
                    Elements sectionWraps = secListElem.select("> div.s_sectionwrap");

                    for (int j = 0; j < sections.size(); j++) {
                        if (Thread.currentThread().isInterrupted()) return;
                        String secTitle = sections.get(j).attr("title");
                        taskLogger.log(task.getId(), String.format("  └─ [小节] %s", secTitle));

                        Element wrapElem = j < sectionWraps.size() ? sectionWraps.get(j) : null;
                        if (wrapElem == null) continue;

                        List<Element> pointsList = new ArrayList<>();
                        Elements pointWraps = wrapElem.select("> div.s_pointwrap");
                        if (!pointWraps.isEmpty()) {
                            for (Element pw : pointWraps) {
                                pointsList.addAll(pw.select("div.s_point"));
                            }
                        } else {
                            pointsList.addAll(wrapElem.select("div.s_point"));
                        }

                        for (Element pt : pointsList) {
                            if (Thread.currentThread().isInterrupted()) return;
                            String ptTitle   = pt.select("div.s_pointti").text();
                            boolean isComplete = "1".equals(pt.attr("completestate"));
                            String onclickValue = pt.attr("onclick");
                            Matcher resourceMatcher = Pattern.compile("openLearnResItem\\('([^']+)',\\s*'([^']+)'").matcher(onclickValue);
                            String type = resourceMatcher.find() ? resourceType(resourceMatcher.group(2)) : "unsupported";
                            String resourceId = "zy/" + Crypto.hash(onclickValue);
                            String parent = "zy/" + i + "/" + j;
                            task.getResources().add(new CourseResource(resourceId, parent, chapTitle + " / " + secTitle + " / " + ptTitle, type, isComplete));
                            if (task.isInspection()) continue;
                            if (!ExecutionScope.current().includes(resourceId, parent, type)) continue;
                            if (!isComplete) ExecutionScope.current().begin(resourceId);

                            if (isComplete) {
                                taskLogger.log(task.getId(), String.format("       [已完成] %s", ptTitle));
                            } else {
                                taskLogger.log(task.getId(), String.format("       [处理中] %s", ptTitle));
                                String onclick = pt.attr("onclick");
                                processPointOnClick(client, task, onclick, taskLogger);
                                ExecutionScope.current().checkpoint("EXECUTED");
                            }
                        }
                    }

                    int currentProgress = Math.min(99, (int) (((double) (i + 1) / totalChapters) * 100));
                    saveProgress(task, currentProgress, String.format("[%d/%d] %s", i + 1, totalChapters, chapTitle));
                }

                saveProgress(task, 99, "正在核验平台进度");
                task.setStatus("VERIFYING");
                taskLogger.log(task.getId(), "课程 [" + task.getCourseName() + "] 资源处理结束，正在核验平台进度");
                return;

            } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
                log.error("执行职教云刷课任务网络波动/异常 (attempt {}/{}):", outerAttempt, taskOuterRetries, e);
                if (outerAttempt < taskOuterRetries) {
                    taskLogger.log(task.getId(), String.format("[网络波动] 刷课连接遭遇严重网络故障 (%s)，正等待 5 秒后恢复任务执行 (%d/%d)...",
                            e.getClass().getSimpleName() != null ? e.getClass().getSimpleName() : "SSL 握手异常", outerAttempt, taskOuterRetries));
                    try { Thread.sleep(5000L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    task.setStatus("FAILED");
                    task.setErrorMessage(e.getClass().getSimpleName());
                    taskLogger.log(task.getId(), "刷课任务因持久网络断开异常终止：" + e.getClass().getSimpleName());
                }
            }
        }
    }

    private OkHttpClient buildClient(String token) {
        return OkHttpUtil.createClientBuilder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .cookieJar(OkHttpUtil.createInMemoryCookieJar())
                .addInterceptor(chain -> {
                    Request orig = chain.request();
                    Request.Builder builder = orig.newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .header("Cookie", "token=" + token);
                    return chain.proceed(builder.build());
                })
                .build();
    }

    private void signLearn(OkHttpClient client, String courseId, TaskLogger taskLogger, Long taskId) throws Exception {
        String baseUrl = "https://mooc.icve.com.cn/patch/zhzj/dataCheck.action";
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(baseUrl)).newBuilder()
                .addQueryParameter("courseId", courseId)
                .addQueryParameter("checkType", "1")
                .addQueryParameter("sign", "0")
                .addQueryParameter("template", "blue")
                .build();
        Request req1 = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        executeStringWithRetry(client, req1, taskLogger, taskId, "签到激活(一)");

        Request req2 = new Request.Builder()
                .url(url)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        String body = executeStringWithRetry(client, req2, taskLogger, taskId, "签到激活(二)");
        if (StringUtils.hasText(body) && body.startsWith("{")) {
            JsonNode json = objectMapper.readTree(body);
            String redirectUrl = json.path("data").asText(null);
            if (StringUtils.hasText(redirectUrl) && redirectUrl.startsWith("http")) {
                Request req3 = new Request.Builder().url(redirectUrl).get().build();
                executeStringWithRetry(client, req3, taskLogger, taskId, "签到重定向");
            }
        }
    }

    private String getCoursewareIndex(OkHttpClient client, String courseId, TaskLogger taskLogger, Long taskId) throws Exception {
        HttpUrl url = Objects.requireNonNull(
                        HttpUrl.parse("https://course.icve.com.cn/learnspace/learn/learn/templateeight/courseware_index.action"))
                .newBuilder()
                .addQueryParameter("params.courseId", courseId)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeStringWithRetry(client, req, taskLogger, taskId, "获取课程结构");
    }

    private void processPointOnClick(OkHttpClient client, CourseTask task, String onclick, TaskLogger taskLogger) {
        if (!StringUtils.hasText(onclick)) return;
        Pattern pattern = Pattern.compile("openLearnResItem\\('([^']+)',\\s*'([^']+)'");
        Matcher matcher = pattern.matcher(onclick);
        if (!matcher.find()) return;

        String itemId  = matcher.group(1);
        String resType = matcher.group(2);

        try {
            HttpUrl infoUrl = Objects.requireNonNull(
                            HttpUrl.parse("https://course.icve.com.cn/learnspace/course/study/learningTime_queryCourseItemInfo.action"))
                    .newBuilder()
                    .addQueryParameter("itemId", itemId)
                    .build();
            Request infoReq = new Request.Builder()
                    .url(infoUrl)
                    .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                    .build();

            String courseId = task.getCourseId();
            String resolvedItemId = itemId;
            String bodyStr = executeStringWithRetry(client, infoReq, taskLogger, task.getId(), "查询资源信息");
            if (StringUtils.hasText(bodyStr) && bodyStr.startsWith("{")) {
                JsonNode node = objectMapper.readTree(bodyStr);
                if (node.has("item")) {
                    courseId       = node.path("item").path("courseId").asText(courseId);
                    resolvedItemId = node.path("item").path("id").asText(itemId);
                }
            }

            if ("video".equalsIgnoreCase(resType) || "courseware".equalsIgnoreCase(resType)) {
                ExecutionScope.sleep(10_000);
                studyVideoRes(client, task, courseId, resolvedItemId, taskLogger);
            } else {
                ExecutionScope.sleep(1000);
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            taskLogger.log(task.getId(), "       资源打卡异常：" + e.getClass().getSimpleName());
        }
    }

    private void studyVideoRes(OkHttpClient client, CourseTask task, String courseId, String itemId, TaskLogger taskLogger) throws Exception {
        HttpUrl videoUrl = Objects.requireNonNull(
                        HttpUrl.parse("https://course.icve.com.cn/learnspace/learn/weixinCourseware/queryVideoResources.json"))
                .newBuilder()
                .addQueryParameter("params.itemId", itemId)
                .build();
        Request videoReq = new Request.Builder()
                .url(videoUrl)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();

        String videoTimeStr = "";
        String bodyStr = executeStringWithRetry(client, videoReq, taskLogger, task.getId(), "查询视频时长");
        if (StringUtils.hasText(bodyStr) && bodyStr.startsWith("{")) {
            JsonNode json = objectMapper.readTree(bodyStr);
            videoTimeStr = json.path("data").path("videoTime").asText("");
        }

        if (!StringUtils.hasText(videoTimeStr)) return;
        int totalSeconds = parseTimeToSeconds(videoTimeStr);

        int undoTime  = getUndoTime(client, courseId, itemId, videoTimeStr, taskLogger, task.getId());
        int startTime = Math.max(0, totalSeconds - undoTime);

        HttpUrl recordUrl = Objects.requireNonNull(
                        HttpUrl.parse("https://course.icve.com.cn/learnspace/learn/learn/templateeight/include/video_learn_record_detail.action"))
                .newBuilder()
                .addQueryParameter("params.courseId", courseId)
                .addQueryParameter("params.itemId", itemId)
                .addQueryParameter("params.videoTotalTime", videoTimeStr)
                .build();
        Request recordReq = new Request.Builder()
                .url(recordUrl)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        executeStringWithRetry(client, recordReq, taskLogger, task.getId(), "初始化视频详情");

        double speed  = (task.getSpeed() != null) ? task.getSpeed() : 1.0;
        int space     = 120;
        int endTime   = Math.min(totalSeconds, startTime + space);

        while (startTime < totalSeconds) {
            if (Thread.currentThread().isInterrupted()) return;

            saveCourseItemLearnRecord(client, courseId, itemId, taskLogger, task.getId());

            String aesMsg = getAesMsg(client, courseId, itemId, videoTimeStr, startTime, endTime, taskLogger, task.getId());

            HttpUrl longUrl = Objects.requireNonNull(
                            HttpUrl.parse("https://course.icve.com.cn/learnspace/course/study/learningTime_saveVideoLearnDetailRecord.action"))
                    .newBuilder()
                    .addQueryParameter("studyRecord", aesMsg)
                    .addQueryParameter("limitId", itemId)
                    .build();
            Request longReq = new Request.Builder()
                    .url(longUrl)
                    .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                    .build();

            try {
                String longResStr = executeStringWithRetry(client, longReq, taskLogger, task.getId(), "视频播放进度上报");
                if (StringUtils.hasText(longResStr) && longResStr.startsWith("{")) {
                    JsonNode resJson = objectMapper.readTree(longResStr);
                    taskLogger.log(task.getId(), String.format("      ~~~> 视频播放上报: %s (%ds~%ds/%ds)",
                            resJson.path("info").asText("成功"), startTime, endTime, totalSeconds));
                }
            } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
                taskLogger.log(task.getId(), String.format("      ~~~> 视频播放上报重试失败: %s", e.getClass().getSimpleName()));
            }

            startTime = endTime;
            endTime   = Math.min(totalSeconds, startTime + space);
            ExecutionScope.sleep((long) ((space / 2.0 * 1000) / Math.max(0.5, speed)));
        }
    }

    private int getUndoTime(OkHttpClient client, String courseId, String itemId, String videoTotalTime, TaskLogger taskLogger, Long taskId) {
        try {
            HttpUrl url = Objects.requireNonNull(
                            HttpUrl.parse("https://course.icve.com.cn/learnspace/learn/learn/templateeight/include/video_learn_record_detail.action"))
                    .newBuilder()
                    .addQueryParameter("params.courseId", courseId)
                    .addQueryParameter("params.itemId", itemId)
                    .addQueryParameter("params.videoTotalTime", videoTotalTime)
                    .build();
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                    .build();
            String html = executeStringWithRetry(client, req, taskLogger, taskId, "查询进度百分比");
            if (StringUtils.hasText(html)) {
                Document doc = Jsoup.parse(html);
                Elements undoDivs = doc.select("div.trace_undo");
                double totalUndoWidth = 0.0;
                for (Element div : undoDivs) {
                    String style = div.attr("style");
                    Pattern p = Pattern.compile("width:\\s*([\\d.]+)%");
                    Matcher m = p.matcher(style);
                    if (m.find()) {
                        totalUndoWidth += Double.parseDouble(m.group(1));
                    }
                }
                int totalSec = parseTimeToSeconds(videoTotalTime);
                return (int) (totalSec * totalUndoWidth / 100.0);
            }
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("getUndoTime 异常: {}", e.getClass().getSimpleName());
        }
        return parseTimeToSeconds(videoTotalTime);
    }

    private void saveCourseItemLearnRecord(OkHttpClient client, String courseId, String itemId, TaskLogger taskLogger, Long taskId) {
        try {
            HttpUrl url = Objects.requireNonNull(
                            HttpUrl.parse("https://course.icve.com.cn/learnspace/course/study/learningTime_saveCourseItemLearnRecord.action"))
                    .newBuilder()
                    .addQueryParameter("courseId", courseId)
                    .addQueryParameter("studyTime", "300")
                    .addQueryParameter("itemId", itemId)
                    .addQueryParameter("recordType", "0")
                    .build();
            Request req = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                    .build();
            executeStringWithRetry(client, req, taskLogger, taskId, "打卡预热保存");
        } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
            log.warn("saveCourseItemLearnRecord 异常: {}", e.getClass().getSimpleName());
        }
    }

    private String getAesMsg(OkHttpClient client, String courseId, String itemId, String videoTotalTime, int startTime, int endTime, TaskLogger taskLogger, Long taskId) throws Exception {
        int totalSec = parseTimeToSeconds(videoTotalTime);

        HttpUrl saveLtUrl = Objects.requireNonNull(
                        HttpUrl.parse("https://course.icve.com.cn/learnspace/course/study/learningTime_saveLearningTime.action"))
                .newBuilder()
                .addQueryParameter("courseId", courseId)
                .addQueryParameter("studyTime", "300")
                .addQueryParameter("limitId", String.valueOf(totalSec))
                .build();
        Request saveLtReq = new Request.Builder()
                .url(saveLtUrl)
                .post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")))
                .build();
        executeStringWithRetry(client, saveLtReq, taskLogger, taskId, "保存学习时间");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("courseId", courseId + "___");
        params.put("itemId", itemId);
        params.put("time1", formatStr(System.currentTimeMillis(), 20));
        params.put("time2", formatStr(startTime, 20));
        params.put("time3", formatStr(totalSec, 20));
        params.put("time4", formatStr(endTime, 20));
        params.put("videoIndex", 0);
        params.put("time5", formatStr(endTime - startTime, 20));
        params.put("terminalType", 0);

        String jsonStr = objectMapper.writeValueAsString(params).replace(" ", "");
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encrypted = cipher.doFinal(jsonStr.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    private String executeStringWithRetry(OkHttpClient client, Request request, TaskLogger logger, Long taskId, String action) throws Exception {
        ExecutionScope.check();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 401 || response.code() == 403)
                throw new TaskSignal("WAITING_AUTH", "PLATFORM_AUTH_EXPIRED", "职教云凭证失效，请重新认证");
            if (!response.isSuccessful() || response.body() == null)
                throw new TaskSignal("RETRY_WAIT", "PLATFORM_REQUEST_FAILED", "职教云请求失败");
            return response.body().string();
        }
    }

    private String formatStr(long c, int a) {
        String strC = String.valueOf(c);
        int k = strC.length();
        if (k <= 0 || k + 2 > a) return strC;
        int g = a - k - 2;
        long h = 1;
        for (int e = 0; e < g; e++) h *= 10;
        long b = (long) (Math.random() * h);
        String strB = String.valueOf(b);
        if (strB.length() < g) {
            for (int d = strB.length(); d < g; d++) b *= 10;
        }
        StringBuilder sb = new StringBuilder();
        if (k >= 10) sb.append(k);
        else sb.append("0").append(k);
        sb.append(c).append(b);
        return sb.toString();
    }

    private int parseTimeToSeconds(String timeStr) {
        if (!StringUtils.hasText(timeStr)) return 0;
        String[] parts = timeStr.split(":");
        if (parts.length == 3) {
            return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
        } else if (parts.length == 2) {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        }
        return 0;
    }

    private void saveProgress(CourseTask task, int progress, String chapterName) {
        if (task == null || task.isInspection()) return;
        task.setProgress(progress);
        if (StringUtils.hasText(chapterName)) {
            task.setCurrentChapter(chapterName);
        }
        task.setUpdateTime(LocalDateTime.now());
        if (courseTaskMapper != null) {
            try {
                courseTaskMapper.updateById(task);
            } catch (Exception e) {
            ExecutionScope.rethrowControl(e);
                log.error("保存进度异常:", e);
            }
        }
    }
}
