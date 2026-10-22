package com.hdsl.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdsl.server.api.ApiError;
import com.hdsl.server.api.JsonErrors;
import com.hdsl.server.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/** 未认证访问 /api 时返回 JSON 401（不重定向到登录页）。 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper json;

    public JsonAuthenticationEntryPoint(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        JsonErrors.write(json, response, HttpStatus.UNAUTHORIZED,
                new ApiError("UNAUTHORIZED", "未登录或会话已过期，请重新登录。", Map.of(), TraceIdFilter.of(request)));
    }
}
