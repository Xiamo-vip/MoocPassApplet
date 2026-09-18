package top.xiamoi.moocpass.infrastructure;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.util.Map;

public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private Json() {}
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    public static JsonNode read(String value) { return MAPPER.readTree(value == null || value.isBlank() ? "{}" : value); }
    public static <T> T read(String value, Class<T> type) { return MAPPER.readValue(value, type); }
    public static Map<String, Object> map(String value) {
        return MAPPER.convertValue(read(value), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
}
