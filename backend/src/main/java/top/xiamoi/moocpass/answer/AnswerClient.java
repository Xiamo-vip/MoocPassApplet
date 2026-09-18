package top.xiamoi.moocpass.answer;

import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.usage.UsageService;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class AnswerClient {
    private final ProfileService profiles;
    private final OutboundHttp http;
    private final UsageService usage;
    public AnswerClient(ProfileService profiles, OutboundHttp http, UsageService usage) {
        this.profiles = profiles; this.http = http; this.usage = usage;
    }
    public record Answer(String text, String answer, String source, long elapsedMs, Integer inputTokens, Integer outputTokens) {
        public Answer(String text, String source, long elapsedMs, Integer inputTokens, Integer outputTokens) {
            this(text, text, source, elapsedMs, inputTokens, outputTokens);
        }
    }
    public Answer ask(ProfileService.Resolved profile, Long taskId, String type, String question, List<String> options, int blanks) {
        return ask(profile, taskId, type, question, options, blanks, false);
    }
    public Answer ask(ProfileService.Resolved profile, Long taskId, String type, String question, List<String> options, int blanks, boolean bestEffort) {
        if (question == null || question.length() > 12000 || Json.write(options).length() > 12000)
            throw ApiException.invalid("题目内容过长");
        ProfileSettings settings = profile.settings();
        String endpointUrl = ProfileService.endpoint(settings);
        Request.Builder request = new Request.Builder().url(endpointUrl);
        String key = profiles.secret(profile);
        if (!key.isBlank()) request.header(settings.authHeader(), settings.authPrefix() + key);
        int reserveTokens = 0;
        if ("AI".equals(profile.kind())) {
            String typeLabel = switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
                case "single", "0" -> "单选题";
                case "multiple", "1" -> "多选题";
                case "judgement", "3" -> "判断题";
                case "completion", "2" -> "填空题";
                case "subjective", "4" -> "主观题";
                default -> "单选题";
            };
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("【题型】：").append(typeLabel).append("\n");
            promptBuilder.append("【题目】：").append(org.jsoup.Jsoup.parse(question).text()).append("\n");
            if (options != null && !options.isEmpty()) {
                promptBuilder.append("【选项】：\n");
                for (int i = 0; i < options.size(); i++) {
                    promptBuilder.append((char) ('A' + i)).append(". ").append(org.jsoup.Jsoup.parse(options.get(i)).text()).append("\n");
                }
            }
            if ("completion".equals(type) || "2".equals(type)) {
                promptBuilder.append("【填空数量】：").append(Math.max(1, blanks)).append(" 个空\n");
            }
            promptBuilder.append(bestEffort
                ? "请给出你认为概率最高的答案，即使不完全确定也要作答。不要解释或输出Markdown："
                : "请直接给出正确答案，严禁输出任何多余分析、解释或Markdown格式：");
            String userPrompt = promptBuilder.toString();

            String instructions = "你是一个专业的在线课程练习答题助手。请根据提供的题目信息直接给出答案，禁止输出任何解释、解析或Markdown。"
                + "规范：单选只输出一个大写字母（如A）；多选只输出大写字母组合（如ABC）；判断只输出 true 或 false；填空只输出 JSON 字符串数组（如[\"答案1\"]）；主观题直接输出简明文字答案。"
                + (bestEffort ? "即使没有把握，也必须选出最可能的答案；不得输出 UNKNOWN 或拒答。" : "不能确定输出 UNKNOWN。");

            int outputLimit = Math.min(2048, settings.reservedTokens());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", settings.model());
            body.put("messages", List.of(
                Map.of("role", "system", "content", instructions),
                Map.of("role", "user", "content", userPrompt)
            ));
            body.put("stream", false);
            String modelLower = settings.model() == null ? "" : settings.model().toLowerCase(Locale.ROOT);
            if (modelLower.startsWith("o1") || modelLower.startsWith("o3")) {
                body.put("max_completion_tokens", outputLimit);
            } else {
                body.put("max_tokens", outputLimit);
                body.put("temperature", 0.0);
            }
            reserveTokens = Json.write(body).getBytes(StandardCharsets.UTF_8).length + outputLimit + 128;
            request.header("Accept", "application/json");
            request.post(RequestBody.create(Json.write(body), MediaType.get("application/json")));
        } else {
            Map<String, String> fields = new LinkedHashMap<>();
            for (var entry : Optional.ofNullable(settings.data()).orElse(Map.of()).entrySet()) {
                String value = entry.getValue().replace("${title}", question).replace("${options}", String.join("\n", options))
                    .replace("${type}", type);
                fields.put(entry.getKey(), value);
            }
            if ("GET".equals(settings.method())) {
                var url = HttpUrl.get(endpointUrl).newBuilder();
                fields.forEach(url::addQueryParameter);
                request.url(url.build()).get();
            } else request.post(RequestBody.create(Json.write(fields), MediaType.get("application/json")));
        }
        long usageId = usage.reserve(profile, taskId, reserveTokens);
        long started = System.nanoTime();
        try (Response response = http.execute(request.build())) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (!response.isSuccessful() || response.body() == null) {
                String errorBody = "";
                try { if (response.body() != null) errorBody = response.body().string(); } catch (Exception ignored) {}
                String error = response.code() == 401 || response.code() == 403 ? "PROVIDER_AUTH_FAILED"
                    : response.code() == 429 ? "PROVIDER_RATE_LIMITED" : "PROVIDER_UNAVAILABLE";
                usage.settle(usageId, "FAILED", null, null, elapsed, error);
                throw new ApiException(error, "答题接口请求失败（HTTP " + response.code() + "）" + (errorBody.isBlank() ? "" : "：" + (errorBody.length() > 200 ? errorBody.substring(0, 200) : errorBody)), HttpStatus.BAD_GATEWAY);
            }
            byte[] bytes = response.body().byteStream().readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new ApiException("PROVIDER_RESPONSE_INVALID", "答题接口响应过大", HttpStatus.BAD_GATEWAY);
            String responseStr = new String(bytes, StandardCharsets.UTF_8).trim();
            JsonNode root;
            if (responseStr.startsWith("data:") || responseStr.contains("\ndata:")) {
                StringBuilder sseContent = new StringBuilder();
                for (String line : responseStr.split("\r?\n")) {
                    line = line.trim();
                    if (line.startsWith("data:")) {
                        String jsonPart = line.substring(5).trim();
                        if (!jsonPart.equals("[DONE]") && !jsonPart.isBlank()) {
                            try {
                                JsonNode chunk = Json.read(jsonPart);
                                String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                                if (delta.isEmpty()) {
                                    delta = chunk.path("choices").path(0).path("message").path("content").asText("");
                                }
                                sseContent.append(delta);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (!sseContent.isEmpty()) {
                    root = Json.MAPPER.createObjectNode()
                        .set("choices", Json.MAPPER.createArrayNode().add(
                            Json.MAPPER.createObjectNode().set("message",
                                Json.MAPPER.createObjectNode().put("content", sseContent.toString())
                            )
                        ));
                } else {
                    root = Json.read(responseStr);
                }
            } else {
                root = Json.read(responseStr);
            }
            if ("TIKU".equals(profile.kind()) && settings.successPath() != null && !settings.successPath().isBlank()
                && !field(root, settings.successPath()).asText("").equals(settings.successValue()))
                throw new ApiException("PROVIDER_NO_ANSWER", "题库未返回有效答案", HttpStatus.BAD_GATEWAY);
            String answer = extractAnswer(root, profile.kind(), settings);
            Integer input = nullableInt(root.path("usage").path("prompt_tokens"));
            Integer output = nullableInt(root.path("usage").path("completion_tokens"));
            if (answer.isBlank() || answer.length() > 20000)
                throw new ApiException("PROVIDER_RESPONSE_INVALID", "答题接口没有返回预期的答案字段", HttpStatus.BAD_GATEWAY);
            usage.settle(usageId, "SUCCESS", input, output, elapsed, "");
            return new Answer(answer, answer, profile.kind(), elapsed, input, output);
        } catch (ApiException error) {
            usage.settle(usageId, "FAILED", null, null, (System.nanoTime() - started) / 1_000_000, error.code());
            throw error;
        } catch (Exception error) {
            usage.settle(usageId, "UNKNOWN", null, null, (System.nanoTime() - started) / 1_000_000, "PROVIDER_RESPONSE_UNKNOWN");
            throw new ApiException("PROVIDER_RESPONSE_UNKNOWN", "答题接口超时或响应无法解析，本次用量暂未知", HttpStatus.BAD_GATEWAY);
        }
    }
    public static JsonNode field(JsonNode node, String path) {
        if (path == null || path.isBlank()) return node;
        for (String part : path.split("\\.")) {
            node = part.matches("\\d+") ? node.path(Integer.parseInt(part)) : node.path(part);
        }
        return node;
    }
    private String extractAnswer(JsonNode root, String kind, ProfileSettings settings) {
        if ("AI".equals(kind)) {
            for (String path : List.of(
                "choices.0.message.content",
                "choices.0.delta.content",
                "choices.0.text",
                "output.choices.0.message.content",
                "output.text",
                "content.0.text",
                "candidates.0.content.parts.0.text",
                "result",
                "output_text",
                "answer",
                "choices.0.message.reasoning_content"
            )) {
                String value = nodeText(field(root, path));
                String clean = value.replaceAll("(?s)<think>.*?</think>", "").trim();
                if (!clean.isBlank()) return clean;
                if (!value.isBlank()) return value;
            }
            JsonNode choices = root.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    String value = nodeText(choice.path("message").path("content"));
                    String clean = value.replaceAll("(?s)<think>.*?</think>", "").trim();
                    if (!clean.isBlank()) return clean;
                    value = nodeText(choice.path("text"));
                    clean = value.replaceAll("(?s)<think>.*?</think>", "").trim();
                    if (!clean.isBlank()) return clean;
                }
            }
            return "";
        }
        return nodeText(field(root, settings.answerPath()));
    }
    private String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText("").trim();
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : node) {
                String value = nodeText(item);
                if (!value.isBlank()) parts.add(value);
            }
            return String.join("\n", parts).trim();
        }
        if (node.isObject()) {
            for (String key : List.of("text", "content", "answer", "value")) {
                String value = nodeText(node.path(key));
                if (!value.isBlank()) return value;
            }
            return "";
        }
        return node.asText("").trim();
    }
    private Integer nullableInt(JsonNode node) { return node.isNumber() ? Math.max(0, node.asInt()) : null; }
}
