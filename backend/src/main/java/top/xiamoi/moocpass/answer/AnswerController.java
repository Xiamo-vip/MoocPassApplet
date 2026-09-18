package top.xiamoi.moocpass.answer;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import top.xiamoi.moocpass.common.*;
import top.xiamoi.moocpass.usage.UsageService;
import top.xiamoi.moocpass.infrastructure.Json;
import java.util.*;

@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class AnswerController {
    private final ProfileService profiles;
    private final AnswerClient client;
    private final UsageService usage;
    public AnswerController(ProfileService profiles, AnswerClient client, UsageService usage) {
        this.profiles = profiles; this.client = client; this.usage = usage;
    }
    @GetMapping("/answer-profiles")
    public ApiResponse<?> list() { return ApiResponse.ok(profiles.list(RequestContext.userId())); }
    @PostMapping("/answer-profiles")
    public ApiResponse<?> create(@RequestBody ProfileService.Input input) {
        return ApiResponse.ok(profiles.save(RequestContext.userId(), null, input));
    }
    @PatchMapping("/answer-profiles/{id}")
    public ApiResponse<?> update(@PathVariable long id, @RequestBody ProfileService.Input input) {
        return ApiResponse.ok(profiles.save(RequestContext.userId(), id, input));
    }
    @DeleteMapping("/answer-profiles/{id}")
    public ApiResponse<?> delete(@PathVariable long id) {
        profiles.delete(RequestContext.userId(), id);
        return ApiResponse.ok(Map.of("deleted", true));
    }
    @PostMapping("/answer-profiles/{id}/test")
    public ApiResponse<?> test(@PathVariable long id) {
        var profile = profiles.resolve(RequestContext.userId(), id, null);
        var options = List.of("A. 1", "B. 2");
        var answer = client.ask(profile, null, "single", "1 + 1 等于多少？", options, 1);
        String ansDisplay = answer.text().trim().equals("B") ? "B (对应选项: 2)" : answer.text();
        return ApiResponse.ok(Map.of(
            "question", "1 + 1 等于多少？",
            "options", options,
            "text", ansDisplay,
            "answer", ansDisplay,
            "source", answer.source(),
            "elapsedMs", answer.elapsedMs(),
            "inputTokens", answer.inputTokens() == null ? 0 : answer.inputTokens(),
            "outputTokens", answer.outputTokens() == null ? 0 : answer.outputTokens()
        ));
    }
    @GetMapping("/usage")
    public ApiResponse<?> usage(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(usage.list(RequestContext.userId(), days));
    }
    @PostMapping("/answer-profiles/import")
    public ApiResponse<?> importConfig(@RequestBody Map<String, Object> input) {
        if (Json.write(input).length() > 16000) throw ApiException.invalid("导入配置过大");
        var root = Json.MAPPER.valueToTree(input);
        if (root.has("handler") || root.path("data").toString().contains("\"handler\"")
            || "GM_xmlhttpRequest".equals(root.path("type").asText()))
            throw ApiException.invalid("该配置含 OCS 脚本或 GM 请求，请改用字段路径映射");
        var url = okhttp3.HttpUrl.get(root.path("url").asText(""));
        String base = url.scheme() + "://" + url.host();
        Map<String, String> data = new LinkedHashMap<>();
        var node = root.path("data");
        if (node.isObject()) node.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) throw ApiException.invalid("题库模板只支持字符串变量");
            data.put(entry.getKey(), entry.getValue().asText());
        });
        if (url.query() != null || !root.path("headers").isMissingNode() && root.path("headers").size() > 0)
            throw ApiException.invalid("请先移除地址中的查询参数和密钥请求头，再在编辑页配置");
        String answerPath = root.path("answerPath").asText("");
        if (answerPath.isBlank()) throw ApiException.invalid("需要提供 answerPath 字段路径");
        var settings = new ProfileSettings(base, url.encodedPath(), "", root.path("method").asText("POST").toUpperCase(Locale.ROOT),
            data, answerPath, "", "", "Authorization", "Bearer ", 3, 500, 200, 0, 4096);
        return ApiResponse.ok(profiles.save(RequestContext.userId(), null,
            new ProfileService.Input(root.path("name").asText("导入题库"), "TIKU", null, false, false, settings)));
    }
}
