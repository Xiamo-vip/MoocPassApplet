package top.xiamoi.moocpass.common;

public record ApiResponse<T>(String code, String message, T data, String requestId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", "", data, RequestContext.requestId());
    }
}
