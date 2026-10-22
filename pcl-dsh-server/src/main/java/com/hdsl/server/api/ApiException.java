package com.hdsl.server.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** 可预期的业务错误：携带稳定的 code 与对应 HTTP 状态，由全局异常处理器转换为 ApiError。 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApiException(String code, HttpStatus status, String message) {
        this(code, status, message, Map.of());
    }

    public ApiException(String code, HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException validation(String message) {
        return new ApiException("VALIDATION", HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException("BAD_REQUEST", HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflictBusy() {
        return new ApiException("CONFLICT_BUSY", HttpStatus.CONFLICT, "当前有任务正在执行，请等待完成或取消。");
    }

    public static ApiException internal(String message) {
        return new ApiException("INTERNAL", HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /** 409：带稳定 code 的业务冲突（如运行中实例、运行时被引用、增量未支持等）。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(code, HttpStatus.CONFLICT, message);
    }

    /** 502/504：上游（npm registry / 代理）查询失败，消息保持中文可操作。 */
    public static ApiException upstream(String code, HttpStatus status, String message) {
        return new ApiException(code, status, message);
    }

    public static ApiException locked(String message, long retryAfterSeconds) {
        return new ApiException("LOGIN_LOCKED", HttpStatus.LOCKED, message,
                Map.of("retryAfterSeconds", Math.max(1, retryAfterSeconds)));
    }
}
