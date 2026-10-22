package com.hdsl.server.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 统一错误模型（方案 §4.10）：所有 /api 错误都返回
 * {@code {code,message,details,traceId}}，且不向客户端泄露内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException exception, HttpServletRequest request) {
        return build(exception.status(), exception.code(), exception.getMessage(), exception.details(), request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleValidation(BindException exception, HttpServletRequest request) {
        String message = "参数校验失败。";
        FieldError first = exception.getBindingResult().getFieldError();
        if (first != null && first.getDefaultMessage() != null && !first.getDefaultMessage().isBlank()) {
            message = "参数校验失败：" + first.getDefaultMessage();
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION", message, null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception,
                                                     HttpServletRequest request) {
        String message = "参数校验失败。";
        FieldError first = exception.getBindingResult().getFieldError();
        if (first != null && first.getDefaultMessage() != null && !first.getDefaultMessage().isBlank()) {
            message = "参数校验失败：" + first.getDefaultMessage();
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION", message, null, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException exception,
                                                     HttpServletRequest request) {
        String detail = exception.getConstraintViolations().stream()
                .findFirst().map(v -> v.getMessage()).orElse("参数不合法");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION", "参数校验失败：" + detail, null, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception,
                                                     HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体不是有效的 JSON 或字段类型不正确。", null, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                       HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "参数格式不正确：" + exception.getName(), null, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException exception,
                                                           HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "缺少必需参数：" + exception.getParameterName(), null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException exception,
                                                 HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "不支持的请求方法：" + exception.getMethod(), null, request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在。", null, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException exception,
                                                     HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "上传内容超出大小限制。", null, request);
    }

    @ExceptionHandler(CsrfException.class)
    public ResponseEntity<ApiError> handleCsrf(CsrfException exception, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "CSRF_INVALID",
                "请求校验失败（CSRF Token 缺失或已过期），请刷新页面后重试。", null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception,
                                                       HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "没有权限执行此操作。", null, request);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> handleIo(IOException exception, HttpServletRequest request) {
        log.warn("数据读写失败 traceId={}：{}", TraceIdFilter.of(request), exception.toString());
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "数据读写失败，请查看服务端日志。", null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("未处理的服务器异常 traceId={}", TraceIdFilter.of(request), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                "服务器内部错误，请稍后重试；如需排查，请提供 traceId。", null, request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           java.util.Map<String, Object> details, HttpServletRequest request) {
        ApiError error = new ApiError(code, message, details, TraceIdFilter.of(request));
        return ResponseEntity.status(status).body(error);
    }
}
