package top.xiamoi.moocpass.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthController {
    @GetMapping({"/health/live", "/api/v1/health/live"})
    public ApiResponse<?> live() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }

    @GetMapping({"/health/ready", "/api/v1/health/ready"})
    public ApiResponse<?> ready() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }
}
