package top.xiamoi.moocpass.common;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public ApiException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    public static ApiException invalid(String message) {
        return new ApiException("INVALID_REQUEST", message, HttpStatus.BAD_REQUEST);
    }
    public static ApiException missing() {
        return new ApiException("RESOURCE_NOT_FOUND", "记录不存在或不可访问", HttpStatus.NOT_FOUND);
    }
    public static ApiException conflict(String message) {
        return new ApiException("RESOURCE_CONFLICT", message, HttpStatus.CONFLICT);
    }
}
