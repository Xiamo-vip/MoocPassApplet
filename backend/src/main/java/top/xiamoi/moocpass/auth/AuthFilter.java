package top.xiamoi.moocpass.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.OncePerRequestFilter;
import top.xiamoi.moocpass.common.*;
import top.xiamoi.moocpass.infrastructure.Json;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("api")
public class AuthFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final Map<String, Window> loginAttempts = new ConcurrentHashMap<>();
    public AuthFilter(AuthService auth) { this.auth = auth; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        RequestContext.requestId(UUID.randomUUID().toString());
        response.setHeader("X-Request-Id", RequestContext.requestId());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key, X-Request-Id");
        response.setHeader("Access-Control-Max-Age", "3600");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        try {
            String path = request.getRequestURI();
            if (path.equals("/api/v1/auth/wechat") || path.equals("/api/v1/auth/refresh")) {
                limitLogin(request.getRemoteAddr());
            } else if (path.startsWith("/api/v1/")) {
                String header = request.getHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ")) throw AuthService.unauthorized();
                RequestContext.userId(auth.authenticate(header.substring(7)));
            }
            chain.doFilter(request, response);
        } catch (ApiException error) {
            response.setStatus(error.status().value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(Json.write(
                new ApiResponse<>(error.code(), error.getMessage(), null, RequestContext.requestId())));
        } finally { RequestContext.clear(); }
    }
    private void limitLogin(String address) {
        long minute = System.currentTimeMillis() / 60000;
        if (loginAttempts.size() > 2000) loginAttempts.entrySet().removeIf(entry -> entry.getValue().minute < minute);
        Window current = loginAttempts.compute(address, (key, old) ->
            old == null || old.minute != minute ? new Window(minute, 1) : new Window(minute, old.count + 1));
        if (current.count > 30) throw new ApiException("RATE_LIMITED", "登录请求过于频繁，请稍后重试",
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }
    private record Window(long minute, int count) {}
}
