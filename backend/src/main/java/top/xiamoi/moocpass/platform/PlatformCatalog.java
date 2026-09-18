package top.xiamoi.moocpass.platform;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.config.MoocProperties;
import java.util.*;

@Service
public class PlatformCatalog {
    private final MoocProperties properties;
    private final PlatformAdapterRegistry registry;
    public PlatformCatalog(MoocProperties properties, PlatformAdapterRegistry registry) {
        this.properties = properties; this.registry = registry;
    }
    public List<Map<String, Object>> list() {
        return List.of(info("chaoxing", "学不通", "PASSWORD", properties.isChaoxingEnabled(), true),
            info("zhy", "吱叫云 / AI 优课", "TOKEN", properties.isZhyEnabled(), false));
    }
    private Map<String, Object> info(String code, String name, String loginMode, boolean enabled, boolean practice) {
        return Map.of("code", code, "name", name, "loginMode", loginMode, "enabled", enabled,
            "status", enabled ? "AVAILABLE" : "PENDING_VERIFICATION",
            "supportsPractice", practice, "supportsResume", true,
            "resourceTypes", practice ? List.of("video", "audio", "document", "read", "workid") : List.of("video", "audio", "document", "read"),
            "speedRange", List.of(1.0, 2.0),
            "description", enabled ? "可绑定账号并同步课程" : "等待管理员完成平台联调后开放");
    }
    public MoocPlatformAdapter require(String code) {
        var adapter = registry.getAdapter(code);
        if (adapter == null) throw ApiException.invalid("不支持的网课平台");
        boolean enabled = "chaoxing".equals(code) ? properties.isChaoxingEnabled() : properties.isZhyEnabled();
        if (!enabled) throw new ApiException("CAPABILITY_UNSUPPORTED", "该平台尚未开放", HttpStatus.CONFLICT);
        return adapter;
    }
}
