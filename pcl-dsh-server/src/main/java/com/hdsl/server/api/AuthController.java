package com.hdsl.server.api;

import com.hdsl.server.config.PclProperties;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.security.ClientIp;
import com.hdsl.server.security.LoginRateLimiter;
import com.hdsl.server.security.ServerCredentials;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 单管理员密码认证：登录/登出/登录态探测/修改密码。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_COOKIE = "JSESSIONID";

    private final ServerCredentials credentials;
    private final LoginRateLimiter rateLimiter;
    private final AuditLog audit;
    private final SecurityContextRepository securityContextRepository;
    private final PclProperties properties;

    public AuthController(ServerCredentials credentials, LoginRateLimiter rateLimiter, AuditLog audit,
                          SecurityContextRepository securityContextRepository, PclProperties properties) {
        this.credentials = credentials;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.securityContextRepository = securityContextRepository;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody(required = false) ApiDtos.LoginRequest body,
                                      HttpServletRequest request, HttpServletResponse response) {
        String ip = ClientIp.of(request);
        long lockSeconds = Math.max(1, properties.getLoginLock().toSeconds());
        if (rateLimiter.isLocked(ip)) {
            audit.record("LOGIN_LOCKED", ip, "来源 IP 处于登录锁定窗口内");
            throw ApiException.locked("登录失败次数过多，已临时锁定，请在 " + lockSeconds + " 秒后重试。", lockSeconds);
        }
        String password = body == null ? null : body.password();
        if (!credentials.verify(password)) {
            boolean locked = rateLimiter.recordFailure(ip);
            audit.record("LOGIN_FAILURE", ip, locked ? "连续失败达到上限，已临时锁定" : "密码错误");
            if (locked) {
                throw ApiException.locked("密码错误次数过多，请 " + lockSeconds + " 秒后再试。", lockSeconds);
            }
            throw new ApiException("LOGIN_FAILED", HttpStatus.UNAUTHORIZED, "密码不正确。");
        }
        rateLimiter.recordSuccess(ip);
        // 会话固定攻击防护：登录成功时废弃旧会话并建立新会话。
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            try {
                existing.invalidate();
            } catch (IllegalStateException ignored) {
                // 会话已失效，继续创建新会话。
            }
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        audit.record("LOGIN_SUCCESS", ip, "");
        return ResponseEntity.noContent().build();
    }

    /** 登出：销毁会话并清除 Cookie；匿名时也返回 204，便于前端统一处理。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String ip = ClientIp.of(request);
        boolean wasAuthenticated = isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 已失效即可。
            }
        }
        SecurityContextHolder.clearContext();
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setMaxAge(0);
        String contextPath = request.getContextPath();
        cookie.setPath(contextPath == null || contextPath.isEmpty() ? "/" : contextPath);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
        if (wasAuthenticated) {
            audit.record("LOGOUT", ip, "");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ApiDtos.MeDto me(CsrfToken csrfToken) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = isAuthenticated(authentication);
        String token = csrfToken == null ? "" : csrfToken.getToken();
        return new ApiDtos.MeDto(authenticated, token, properties.getVersion(), credentials.initialPasswordPending());
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody(required = false) ApiDtos.PasswordChangeRequest body,
                                               HttpServletRequest request) {
        if (body == null) {
            throw ApiException.validation("请输入原密码与新密码。");
        }
        credentials.changePassword(body.oldPassword(), body.newPassword());
        audit.record("PASSWORD_CHANGED", ClientIp.of(request), "管理员密码已修改");
        return ResponseEntity.noContent().build();
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
