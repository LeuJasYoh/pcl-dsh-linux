package com.hdsl.server.state;

import com.hdsl.account.AccountEntry;
import com.hdsl.account.AccountService;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.config.PclProperties;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.runtime.RuntimeCompatibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把 pcl-dsh-core 的业务对象组装成 REST DTO（方案 §4.9）。
 * 只做只读聚合，不重复实现业务逻辑；账户资料损坏时 accounts 返回空列表并在 warnings
 * 中给出中文提示，保证 GET /api/state 不返回 500、也不泄漏文件路径。
 */
@Component
public class StateAssembler {

    private static final Logger log = LoggerFactory.getLogger(StateAssembler.class);
    private static final String DEFAULT_REGISTRY = "https://registry.npmjs.org";
    /** 供 /api/state warnings 与前端提示使用；不含文件路径等内部细节。 */
    public static final String ACCOUNTS_WARNING =
            "账户资料无法读取，账户列表已暂时置空；请检查 data/config/accounts.json 或重新保存 API Key。";

    private final InstanceStore store;
    private final ProcessService processes;
    private final AccountService accounts;
    private final RuntimeProvider runtimes;
    private final JobManager jobs;
    private final PclProperties properties;
    private final RuntimeCompatibility compatibility;

    public StateAssembler(InstanceStore store, ProcessService processes, AccountService accounts,
                          RuntimeProvider runtimes, JobManager jobs, PclProperties properties,
                          RuntimeCompatibility compatibility) {
        this.store = store;
        this.processes = processes;
        this.accounts = accounts;
        this.runtimes = runtimes;
        this.jobs = jobs;
        this.properties = properties;
        this.compatibility = compatibility;
    }

    public ApiDtos.StateDto state() {
        List<ApiDtos.InstanceDto> instances = instances();
        List<ApiDtos.RuntimeDto> runtimeDtos;
        try {
            runtimeDtos = runtimes();
        } catch (IOException | RuntimeException e) {
            log.warn("读取已安装运行时失败：{}", e.toString());
            runtimeDtos = List.of();
        }
        List<ApiDtos.AccountDto> accountDtos;
        List<String> warnings = new ArrayList<>();
        try {
            accountDtos = accountList();
        } catch (IOException | RuntimeException e) {
            log.warn("读取账户列表失败，按空列表降级：{}", e.toString());
            accountDtos = List.of();
            warnings.add(ACCOUNTS_WARNING);
        }
        JobManager.GlobalStatus status = jobs.globalStatus();
        return new ApiDtos.StateDto(properties.getVersion(), store.selected(), instances, runtimeDtos,
                accountDtos, new ApiDtos.StatusDto(status.code(), status.message()), settings(),
                List.of(), warnings);
    }

    public List<ApiDtos.InstanceDto> instances() {
        List<Instance> rows = store.list();
        List<ApiDtos.InstanceDto> result = new ArrayList<>(rows.size());
        for (Instance instance : rows) {
            result.add(instance(instance));
        }
        return List.copyOf(result);
    }

    /** 单个实例的 DTO（实例写接口复用，保证与列表字段完全一致）。 */
    public ApiDtos.InstanceDto instance(Instance instance) {
        return new ApiDtos.InstanceDto(instance.id(), instance.name(), instance.runtimeVersion(),
                instance.profile(), instance.port(), processes.status(instance),
                store.directory(instance).toString(), profiles(instance), instance.accountId());
    }

    public List<ApiDtos.RuntimeDto> runtimes() throws IOException {
        return runtimes.get().installedVersions().stream()
                .map(version -> new ApiDtos.RuntimeDto(version, true, compatibility.get(version)))
                .toList();
    }

    public List<ApiDtos.AccountDto> accountList() throws IOException {
        List<AccountEntry> rows = accounts.list();
        List<ApiDtos.AccountDto> result = new ArrayList<>(rows.size());
        for (AccountEntry entry : rows) {
            result.add(account(entry));
        }
        return List.copyOf(result);
    }

    /** 单个账户的 DTO（账户写接口复用，保证与列表字段完全一致）。 */
    public ApiDtos.AccountDto account(AccountEntry entry) {
        return new ApiDtos.AccountDto(entry.id(), entry.name(), entry.provider(), entry.baseUrl(),
                entry.model(), entry.hasKey(), entry.status());
    }

    public ApiDtos.SettingsDto settings() {
        String registry = store.setting("registry", DEFAULT_REGISTRY);
        if (registry == null || registry.isBlank()) {
            registry = DEFAULT_REGISTRY;
        }
        return new ApiDtos.SettingsDto(store.setting("proxy", ""), registry, store.setting("background", ""));
    }

    private List<String> profiles(Instance instance) {
        Set<String> names = new TreeSet<>();
        names.add(instance.profile());
        Path directory = store.home(instance).resolve("profiles");
        if (Files.isDirectory(directory)) {
            try (var stream = Files.list(directory)) {
                stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                && !path.getFileName().toString().equals("node_modules"))
                        .forEach(path -> names.add(path.getFileName().toString()));
            } catch (IOException ignored) {
                // 只读展示：profile 目录不可读时退化为实例自身的 profile。
            }
        }
        return List.copyOf(names);
    }
}
