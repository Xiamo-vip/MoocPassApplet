package top.xiamoi.moocpass.auth;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import top.xiamoi.moocpass.common.*;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/auth/wechat")
    public ApiResponse<?> login(@RequestBody LoginRequest request) { return ApiResponse.ok(auth.login(request.code(), request.openid())); }
    @PostMapping("/auth/refresh")
    public ApiResponse<?> refresh(@RequestBody RefreshRequest request) { return ApiResponse.ok(auth.refresh(request.refreshToken())); }
    @PostMapping("/auth/logout")
    public ApiResponse<?> logout(@RequestHeader("Authorization") String header) {
        auth.logout(RequestContext.userId(), header.substring(7));
        return ApiResponse.ok(Map.of("revoked", true));
    }
    @GetMapping("/me")
    public ApiResponse<?> me() { return ApiResponse.ok(auth.user(RequestContext.userId())); }
    @PatchMapping("/me")
    public ApiResponse<?> rename(@RequestBody RenameRequest request) {
        return ApiResponse.ok(auth.rename(RequestContext.userId(), request.nickname()));
    }
    public record LoginRequest(String code, String openid) {}
    public record RefreshRequest(String refreshToken) {}
    public record RenameRequest(String nickname) {}
}
