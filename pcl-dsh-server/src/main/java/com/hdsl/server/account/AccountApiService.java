package com.hdsl.server.account;

import com.hdsl.account.AccountEntry;
import com.hdsl.account.AccountService;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.state.StateAssembler;
import com.hdsl.server.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 账户接口业务（方案 §4.2 / §4.5）：provider 预设、账户 CRUD、被实例引用保护。
 *
 * <p>复用 pcl-dsh-core 的 {@link AccountService}（密钥 AES-GCM 加密存储）；
 * 本类与 DTO 永不包含密钥/密文，审计与日志只记录 id、provider、名称。</p>
 */
@Component
public class AccountApiService {

    private static final Logger log = LoggerFactory.getLogger(AccountApiService.class);
    /** 账户损坏降级审计限频：避免 /api/state 轮询导致审计日志刷屏。 */
    private static final long DEGRADED_AUDIT_INTERVAL_MILLIS = 5 * 60_000L;
    public static final String DEGRADED_WARNING = "ACCOUNTS_UNREADABLE";

    private volatile long lastDegradedAuditAt;

    /** 账户列表结果：账户资料损坏时返回空列表 + 中文警告，而不是 500。 */
    public record AccountList(List<ApiDtos.AccountDto> accounts, String warning) {
        public AccountList {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            warning = warning == null ? "" : warning;
        }

        public boolean degraded() {
            return !warning.isEmpty();
        }
    }

    private final AccountService accounts;
    private final InstanceStore store;
    private final JobManager jobs;
    private final AuditLog audit;
    private final LogFiles logs;
    private final StateAssembler state;

    public AccountApiService(AccountService accounts, InstanceStore store, JobManager jobs, AuditLog audit,
                             LogFiles logs, StateAssembler state) {
        this.accounts = accounts;
        this.store = store;
        this.jobs = jobs;
        this.audit = audit;
        this.logs = logs;
        this.state = state;
    }

    public List<ApiDtos.ProviderDto> providers() {
        return AccountService.providers().stream()
                .map(preset -> new ApiDtos.ProviderDto(preset.id(), preset.name(), preset.baseUrl(), preset.model()))
                .toList();
    }

    public AccountList list(String ip) {
        try {
            return new AccountList(state.accountList(), "");
        } catch (IOException | RuntimeException failure) {
            log.warn("读取账户列表失败，按空列表降级：{}", failure.toString());
            recordDegradedAudit(ip);
            return new AccountList(List.of(), StateAssembler.ACCOUNTS_WARNING);
        }
    }

    /** 降级审计：最多每 5 分钟记录一次，避免轮询刷日志；不记录异常原文/文件路径。 */
    private void recordDegradedAudit(String ip) {
        long now = System.currentTimeMillis();
        if (now - lastDegradedAuditAt < DEGRADED_AUDIT_INTERVAL_MILLIS) {
            return;
        }
        lastDegradedAuditAt = now;
        audit.record("ACCOUNTS_DEGRADED", ip, "账户资料无法读取，接口已按空列表降级，需重新保存 API Key");
        logs.append("账户资料无法读取，已按空列表降级并记录审计。");
    }

    public ApiDtos.AccountDto create(ApiDtos.AccountSaveRequest request, String ip) {
        jobs.ensureIdle();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空。");
        }
        AccountEntry entry = save("", request);
        audit.record("ACCOUNT_CREATED", ip, "id=" + entry.id() + " provider=" + entry.provider()
                + " name=" + LogSanitizer.oneLine(entry.name(), 64));
        logs.append("已创建账户：" + entry.name() + "（" + entry.provider() + "）");
        return state.account(entry);
    }

    public ApiDtos.AccountDto update(String id, ApiDtos.AccountSaveRequest request, String ip) {
        jobs.ensureIdle();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空。");
        }
        AccountEntry entry = save(id, request);
        audit.record("ACCOUNT_UPDATED", ip, "id=" + entry.id() + " provider=" + entry.provider()
                + " name=" + LogSanitizer.oneLine(entry.name(), 64));
        logs.append("已更新账户：" + entry.name() + "（" + entry.provider() + "）");
        return state.account(entry);
    }

    public String delete(String id, String ip) {
        jobs.ensureIdle();
        String value = id == null ? "" : id.trim();
        boolean referenced = store.list().stream().anyMatch(instance -> value.equals(instance.accountId()));
        if (referenced) {
            throw new ApiException("CONFLICT_IN_USE", HttpStatus.CONFLICT,
                    "此账户仍被实例使用，请先在实例设置中解除绑定。");
        }
        try {
            accounts.delete(value);
        } catch (IOException failure) {
            throw accountFailure(failure, "删除");
        }
        audit.record("ACCOUNT_DELETED", ip, "id=" + value);
        logs.append("已删除账户：" + value);
        return value;
    }

    private AccountEntry save(String id, ApiDtos.AccountSaveRequest request) {
        String baseUrl = request.baseUrl() == null ? "" : request.baseUrl();
        String apiKey = request.apiKey() == null ? "" : request.apiKey();
        try {
            return accounts.save(id, request.name(), request.provider(), baseUrl, request.model(), apiKey);
        } catch (IOException failure) {
            throw accountFailure(failure, "保存");
        }
    }

    /** 把 core 的中文校验消息映射为 400/404，其余（存储/密钥损坏）映射为 500 并保留可操作中文提示。 */
    private ApiException accountFailure(IOException failure, String action) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("不存在")) {
            return ApiException.notFound(message);
        }
        if (message.startsWith("无效账户编号") || message.startsWith("账户名称") || message.startsWith("无效提供商")
                || message.startsWith("API 地址") || message.startsWith("API Key") || message.startsWith("新账户")
                || message.startsWith("模型")) {
            return ApiException.validation(message);
        }
        if (message.startsWith("账户资料") || message.startsWith("无法加密") || message.contains("账户加密数据")) {
            return ApiException.internal(LogSanitizer.oneLine(message, 240));
        }
        log.warn("账户{}失败：{}", action, failure.toString());
        return ApiException.internal("账户" + action + "失败，请检查数据目录权限后重试。");
    }
}
