package com.hdsl.server.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 为每个请求生成短 traceId，写入请求属性、MDC 与响应头 X-Trace-Id，便于与错误响应/日志对应。 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "pcl.traceId";
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String text ? text : "";
    }
}
