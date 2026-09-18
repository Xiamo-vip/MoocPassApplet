package top.xiamoi.moocpass.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> business(ApiException error) {
        return ResponseEntity.status(error.status()).body(
            new ApiResponse<>(error.code(), error.getMessage(), null, RequestContext.requestId()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        IllegalArgumentException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(
            new ApiResponse<>("INVALID_REQUEST", "请检查提交的字段和格式", null, RequestContext.requestId()));
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> methodNotSupported(HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
            new ApiResponse<>("METHOD_NOT_ALLOWED", "不支持的请求方法", null, RequestContext.requestId()));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            new ApiResponse<>("NOT_FOUND", "请求的资源或路径不存在", null, RequestContext.requestId()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
        LOG.error("requestId={} errorType={} message={}", RequestContext.requestId(), error.getClass().getSimpleName(), error.getMessage());
        return ResponseEntity.internalServerError().body(
            new ApiResponse<>("INTERNAL_ERROR", "服务暂时不可用，请稍后重试", null, RequestContext.requestId()));
    }
}
