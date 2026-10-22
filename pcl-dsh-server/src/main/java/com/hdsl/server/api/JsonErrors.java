package com.hdsl.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;

/** 在 Servlet 过滤器 / Security 回调中直接写出统一 JSON 错误。 */
public final class JsonErrors {

    private JsonErrors() {
    }

    public static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, ApiError error)
            throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), error);
    }
}
