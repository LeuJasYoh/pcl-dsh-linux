package com.hdsl.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdsl.server.api.ApiError;
import com.hdsl.server.api.JsonErrors;
import com.hdsl.server.api.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 首次登录强制改密：初始密码未修改时，除 /api/auth/** 与 /api/health 外一律返回
 * 403 PASSWORD_CHANGE_REQUIRED，避免初始密码长期有效。
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private final ServerCredentials credentials;
    private final ObjectMapper json;

    public PasswordChangeRequiredFilter(ServerCredentials credentials, ObjectMapper json) {
        this.credentials = credentials;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.startsWith("/api/auth/") || path.equals("/api/health")) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        if (authenticated && credentials.initialPasswordPending()) {
            JsonErrors.write(json, response, HttpStatus.FORBIDDEN, new ApiError("PASSWORD_CHANGE_REQUIRED",
                    "首次登录必须先修改初始密码。", Map.of("endpoint", "PUT /api/auth/password"),
                    TraceIdFilter.of(request)));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
