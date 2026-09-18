package top.xiamoi.moocpass.answer;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileSettings(
    String baseUrl, String path, String model, String method,
    @JsonAlias("requestTemplate") Map<String, String> data,
    String answerPath,
    @JsonAlias("successField") String successPath,
    String successValue,
    @JsonAlias("headerName") String authHeader,
    @JsonAlias("headerPrefix") String authPrefix,
    @JsonAlias("timeoutSeconds") int intervalSeconds,
    @JsonAlias("dailyLimit") int dailyRequests,
    @JsonAlias("tokenBudgetPerCall") int taskRequests,
    int dailyTokens,
    @JsonAlias("maxTokens") int reservedTokens) {

    public ProfileSettings {
        if (method == null || method.isBlank()) method = "POST";
        method = method.toUpperCase();
        if (data == null) data = Map.of();
        if (answerPath == null) answerPath = "";
        if (successPath == null) successPath = "";
        if (successValue == null) successValue = "";
        if (authHeader == null || authHeader.isBlank()) authHeader = "Authorization";
        if (authPrefix == null) authPrefix = "Bearer ";
        if (intervalSeconds <= 0) intervalSeconds = 3;
        if (dailyRequests <= 0) dailyRequests = 500;
        if (taskRequests <= 0) taskRequests = Math.min(200, dailyRequests);
        if (reservedTokens <= 0) reservedTokens = 4096;
    }

    public static ProfileSettings defaults(String baseUrl, String model) {
        return new ProfileSettings(baseUrl, "/chat/completions", model, "POST", Map.of(),
            "choices.0.message.content", "", "", "Authorization", "Bearer ", 3, 500, 200, 0, 4096);
    }
}
