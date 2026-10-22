package com.hdsl.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security 6 会话式配置（方案 §4.4）：
 * <ul>
 *   <li>JSESSIONID：HttpOnly + SameSite=Lax（见 application.yml），服务仅监听 127.0.0.1。</li>
 *   <li>CSRF：CookieCsrfTokenRepository（Cookie XSRF-TOKEN + 请求头 X-XSRF-TOKEN），登录接口豁免。</li>
 *   <li>/api 未认证返回 JSON 401，不重定向；CSRF 失败返回 403 CSRF_INVALID。</li>
 *   <li>首次登录未改密时，除 /api/auth/** 与 /api/health 外一律 403 PASSWORD_CHANGE_REQUIRED。</li>
 * </ul>
 *
 * <p>CSRF 过滤器手动装配（而不是只用 {@code http.csrf(...)}），以便给 CsrfFilter 设置
 * JSON 的 AccessDeniedHandler；Spring Security 6.5 的 CsrfConfigurer 未暴露该设置。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ServerCredentials credentials;
    private final ObjectMapper json;

    public SecurityConfig(ServerCredentials credentials, ObjectMapper json) {
        this.credentials = credentials;
        this.json = json;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityContextRepository securityContextRepository,
                                                   JsonAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");

        http
                .csrf(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/api/auth/login", "/api/auth/logout", "/api/auth/me").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);

        CsrfFilter csrfFilter = new CsrfFilter(csrfRepository);
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrfFilter.setAccessDeniedHandler(accessDeniedHandler);
        csrfFilter.setRequireCsrfProtectionMatcher(request ->
                CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request) && !isLoginRequest(request));
        http.addFilterAt(csrfFilter, CsrfFilter.class);
        // 让 SPA 在首个响应就拿到 XSRF-TOKEN Cookie（Spring Security 官方推荐做法）。
        http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
        // 首次登录强制改密：放在鉴权之后，保证匿名 401 仍由入口点处理。
        http.addFilterAfter(new PasswordChangeRequiredFilter(credentials, json), AuthorizationFilter.class);
        return http.build();
    }

    private static boolean isLoginRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "/api/auth/login".equals(path);
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // 触发延迟令牌解析，从而写出 XSRF-TOKEN Cookie；令牌只用于同源请求。
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
