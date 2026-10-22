package com.hdsl.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdsl.server.api.ApiError;
import com.hdsl.server.api.JsonErrors;
import com.hdsl.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** 权限/CSRF 失败时返回统一 JSON（不重定向）。CSRF 失败使用独立 code 便于前端刷新令牌。 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper json;

    public RestAccessDeniedHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        boolean csrf = accessDeniedException instanceof CsrfException
                || accessDeniedException.getCause() instanceof CsrfException;
        if (csrf) {
            JsonErrors.write(json, response, HttpStatus.FORBIDDEN, new ApiError("CSRF_INVALID",
                    "请求校验失败（CSRF Token 缺失或已过期），请刷新页面后重试。", Map.of(),
                    TraceIdFilter.of(request)));
            return;
        }
        JsonErrors.write(json, response, HttpStatus.FORBIDDEN, new ApiError("FORBIDDEN",
                "没有权限执行此操作。", Map.of(), TraceIdFilter.of(request)));
    }
}
