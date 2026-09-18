package top.xiamoi.moocpass.common;

import org.springframework.http.HttpStatus;

public final class RequestContext {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private static final ThreadLocal<String> REQUEST = new ThreadLocal<>();
    private RequestContext() {}
    public static long userId() {
        Long userId = USER.get();
        if (userId == null) throw new ApiException("AUTH_EXPIRED", "请重新登录", HttpStatus.UNAUTHORIZED);
        return userId;
    }
    public static String requestId() { return REQUEST.get(); }
    public static void requestId(String value) { REQUEST.set(value); }
    public static void userId(long value) { USER.set(value); }
    public static void clear() { USER.remove(); REQUEST.remove(); }
}
