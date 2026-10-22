package com.hdsl.server.instance;

import com.hdsl.account.AccountService;
import com.hdsl.account.LaunchBinding;
import com.hdsl.core.Instance;
import com.hdsl.core.InstanceStore;
import com.hdsl.core.ProcessService;
import com.hdsl.pack.PackInspection;
import com.hdsl.pack.PackService;
import com.hdsl.runtime.Capabilities;
import com.hdsl.runtime.RuntimeService;
import com.hdsl.server.api.ApiDtos;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import com.hdsl.server.config.RuntimeProvider;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.log.LogFiles;
import com.hdsl.server.security.AuditLog;
import com.hdsl.server.state.StateAssembler;
import com.hdsl.server.util.LogSanitizer;
import com.hdsl.server.util.Trash;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 实例生命周期业务（方案 §4.2 / §4.8）：CRUD、选择、账户绑定、启停、启动长任务。
 *
 * <p>业务规则移植自 HDSL {@code Controller.execute/launch/ensureStopped/moveToTrash}，
 * 底层全部复用 pcl-dsh-core 的 {@link InstanceStore}/{@link ProcessService}/
 * {@link RuntimeService}/{@link AccountService}/{@link LaunchBinding}，本类只做编排与
 * 中文错误映射。Controller 仅负责 HTTP 参数校验与调用本服务。</p>
 *
 * <p>同步写操作先 {@link JobManager#ensureIdle()}（忙时 409 CONFLICT_BUSY）；长任务走
 * {@link JobManager#submit}，日志经 {@link JobManager.Context#log} 同时进入 SSE 与 data/logs。</p>
 */
@Component
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private static final String CONFLICT_INSTANCE_RUNNING = "CONFLICT_INSTANCE_RUNNING";
    private static final String CONFLICT_PORT_BUSY = "CONFLICT_PORT_BUSY";
    private static final String CONFLICT_RUNNING_INSTANCE = "CONFLICT_RUNNING_INSTANCE";
    private static final String CONFLICT_CUSTOM_COMMAND = "CONFLICT_CUSTOM_COMMAND";
    private static final String INSTANCE_NOT_READY = "INSTANCE_NOT_READY";

    private static final Duration LAUNCH_TIMEOUT = Duration.ofMinutes(22);
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration BINDING_VALIDATION_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(30);

    /** PUT /api/instances/{id} 的结果：普通修改返回 instance（200），version/profile 变更返回 job（202）。 */
    public record UpdateOutcome(ApiDtos.InstanceDto instance, JobManager.View job) {
    }

    /** 一次复制/改版本任务的目标参数。 */
    private record CopySpec(String name, String version, String profile, String accountId, String ip) {
    }

    private final InstanceStore store;
    private final ProcessService processes;
    private final AccountService accounts;
    private final RuntimeProvider runtimes;
    private final JobManager jobs;
    private final DataRoot data;
    private final AuditLog audit;
    private final LogFiles logs;
    private final StateAssembler state;

    /** 运行中实例的账户 overlay（密钥只在内存；实例停止/关闭时清理临时文件）。 */
    private final Map<String, LaunchBinding> bindings = new ConcurrentHashMap<>();

    /** 同步操作（停止/启动输出）的日志出口：应用日志文件 + debug 控制台。 */
    private final Consumer<String> processLog;

    public InstanceService(InstanceStore store, ProcessService processes, AccountService accounts,
                           RuntimeProvider runtimes, JobManager jobs, DataRoot data, AuditLog audit,
                           LogFiles logs, StateAssembler state) {
        this.store = store;
        this.processes = processes;
        this.accounts = accounts;
        this.runtimes = runtimes;
        this.jobs = jobs;
        this.data = data;
        this.audit = audit;
        this.logs = logs;
        this.state = state;
        this.processLog = line -> {
            logs.append(line);
            log.debug("{}", line);
        };
    }

    // ------------------------------------------------------------------ 读取

    public ApiDtos.InstanceDto get(String id) {
        return dto(require(id));
    }

    // ------------------------------------------------------------------ 创建 / 编辑 / 删除

    public ApiDtos.InstanceDto create(ApiDtos.CreateInstanceRequest request, String ip) {
        jobs.ensureIdle();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空。");
        }
        String name = trim(request.name());
        String version = trim(request.version());
        String profile = request.profile() == null || request.profile().isBlank() ? "web" : request.profile().trim();
        String accountId = trim(request.accountId());
        validateName(name);
        validateVersion(version);
        validateProfile(profile);
        if (!"web".equals(profile)) {
            throw ApiException.validation("空白实例使用 web 模板；自定义 profile 可通过整合包导入。");
        }
        validateAccount(accountId);
        int port = portFor(request.port(), null);
        if (store.list().stream().anyMatch(row -> row.port() == port)) {
            throw ApiException.validation("端口已被其他实例使用。");
        }
        Instance instance = new Instance(InstanceStore.newId(), name, version, profile, port, "", accountId);
        Path directory = store.directory(instance);
        try {
            Files.createDirectories(store.workspace(instance));
            Files.createDirectories(store.home(instance));
            store.add(instance);
        } catch (IOException failure) {
            // 与桌面版一致：登记失败时把已创建的目录移入回收目录，避免留下无主目录。
            cleanupUnregistered(directory, instance.id());
            throw storeFailure(failure);
        }
        audit.record("INSTANCE_CREATED", ip, "id=" + instance.id() + " version=" + version
                + " port=" + port + " name=" + LogSanitizer.oneLine(name, 64));
        logs.append("已创建实例：" + name + "（" + instance.id() + "）");
        log.info("实例已创建：{}（{}），版本 {}，端口 {}", name, instance.id(), version, port);
        return dto(instance);
    }

    public UpdateOutcome update(String id, ApiDtos.UpdateInstanceRequest request, String ip) {
        jobs.ensureIdle();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空。");
        }
        Instance existing = require(id);
        String requestedVersion = trim(request.version());
        String requestedProfile = trim(request.profile());
        boolean versionChanged = !requestedVersion.isEmpty() && !requestedVersion.equals(existing.runtimeVersion());
        boolean profileChanged = !requestedProfile.isEmpty() && !requestedProfile.equals(existing.profile());
        if (versionChanged || profileChanged) {
            // M3：版本/profile 变更以“复制为新实例”实现（原实例保留），返回 202 Job。
            return new UpdateOutcome(null, requestChangeCopy(existing, request, requestedVersion,
                    requestedProfile, versionChanged, profileChanged, ip));
        }
        ensureStopped(existing);
        String name = trim(request.name());
        if (name.isEmpty()) {
            name = existing.name();
        }
        validateName(name);
        int port = portFor(request.port(), existing.port());
        if (port != existing.port() && store.list().stream()
                .anyMatch(row -> !row.id().equals(id) && row.port() == port)) {
            throw ApiException.validation("端口已分配给其他实例。");
        }
        String accountId = request.accountId() == null ? existing.accountId() : trim(request.accountId());
        validateAccount(accountId);
        Instance updated = new Instance(existing.id(), name, existing.runtimeVersion(), existing.profile(),
                port, existing.customCommand(), accountId);
        try {
            store.replace(updated);
        } catch (IOException failure) {
            throw storeFailure(failure);
        }
        audit.record("INSTANCE_UPDATED", ip, "id=" + id + " port=" + port
                + " name=" + LogSanitizer.oneLine(name, 64));
        logs.append("已更新实例：" + name + "（" + id + "）");
        return new UpdateOutcome(dto(updated), null);
    }

    /** POST /api/instances/{id}/copy：复制为新实例（原实例保留），返回 202 Job。 */
    public JobManager.View requestCopy(String id, String name, String ip) {
        Instance source = require(id);
        ensureStopped(source);
        String targetName = trim(name);
        validateName(targetName);
        CopySpec spec = new CopySpec(targetName, source.runtimeVersion(), source.profile(),
                source.accountId(), ip);
        audit.record("INSTANCE_COPY_REQUESTED", ip, "source=" + id
                + " name=" + LogSanitizer.oneLine(targetName, 64));
        logs.append("请求复制实例：" + source.name() + " → " + targetName);
        return jobs.submit("COPY_INSTANCE", COPY_TIMEOUT, context -> copyInstance(context, id, spec));
    }

    /** PUT 版本/profile 变更的复制任务（目标为新版本/新 profile，原实例保持不变）。 */
    private JobManager.View requestChangeCopy(Instance existing, ApiDtos.UpdateInstanceRequest request,
                                              String requestedVersion, String requestedProfile,
                                              boolean versionChanged, boolean profileChanged, String ip) {
        ensureStopped(existing);
        String name = trim(request.name());
        if (name.isEmpty()) {
            name = existing.name();
        }
        validateName(name);
        String targetVersion = versionChanged ? requestedVersion : existing.runtimeVersion();
        validateVersion(targetVersion);
        String targetProfile = profileChanged ? requestedProfile : existing.profile();
        validateProfile(targetProfile);
        if (!"web".equals(targetProfile) && !Files.isRegularFile(
                store.home(existing).resolve("profiles").resolve(targetProfile).resolve("package.json"))) {
            throw ApiException.validation("这个 profile 尚未初始化，请选择已有 profile 或导入整合包。");
        }
        String accountId = request.accountId() == null ? existing.accountId() : trim(request.accountId());
        validateAccount(accountId);
        CopySpec spec = new CopySpec(name, targetVersion, targetProfile, accountId, ip);
        audit.record("INSTANCE_CHANGE_COPY_REQUESTED", ip, "source=" + existing.id()
                + " version=" + targetVersion + " profile=" + LogSanitizer.oneLine(targetProfile, 64));
        logs.append("请求以复制方式修改实例版本/profile：" + existing.name() + " → " + name);
        return jobs.submit("COPY_INSTANCE", COPY_TIMEOUT, context -> copyInstance(context, existing.id(), spec));
    }

    /** COPY_INSTANCE 任务主体：空白 web 实例直接登记；已初始化实例导出 profile → 检查 → 按导入流程创建新实例。 */
    private Object copyInstance(JobManager.Context context, String sourceId, CopySpec spec) throws Exception {
        Instance source = require(sourceId);
        ensureStopped(source);
        context.checkCancelled();
        if (isBlankWeb(source) && "web".equals(spec.profile())) {
            context.phase("创建空白实例副本");
            Instance target = new Instance(InstanceStore.newId(), spec.name(), spec.version(), "web",
                    availablePort(), "", spec.accountId());
            Path directory = store.directory(target);
            try {
                Files.createDirectories(store.workspace(target));
                Files.createDirectories(store.home(target));
                store.add(target);
            } catch (IOException failure) {
                cleanupUnregistered(directory, target.id());
                throw storeFailure(failure);
            }
            context.log("已复制未初始化的空白实例：" + target.name() + "（" + target.id() + "）");
            logs.append("已复制空白实例：" + target.name() + "（" + target.id() + "）");
            return new ApiDtos.PackImportResultDto(target.id(), target.name());
        }
        Path stagingBase = data.cache().resolve("pack-staging");
        Files.createDirectories(stagingBase);
        Path archive = Files.createTempFile(stagingBase, "copy-", ".dspack");
        try {
            context.phase("导出源实例 profile");
            PackService packs = packs();
            packs.exportProfile(store.home(source), spec.profile(), source.runtimeVersion(), packName(source),
                    "1.0.0", archive, context::log);
            context.checkCancelled();
            context.phase("检查复制产物");
            PackInspection inspection = packs.inspect(archive);
            context.checkCancelled();
            return importInspection(context, inspection, spec.name(), spec.version(), spec.accountId(), spec.ip());
        } finally {
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // 暂存清理尽力而为。
            }
        }
    }

    /**
     * 导入流程主体（移植 HDSL Controller.importPack）：暂存解压 → 安装运行时 → 移动到最终目录 →
     * 恢复各 profile 依赖 → 登记实例；失败把目录移入 trash/failed-import-* 并清理暂存。
     */
    public ApiDtos.PackImportResultDto importInspection(JobManager.Context context, PackInspection inspection,
                                                        String requestedName, String targetVersion,
                                                        String accountId, String ip) throws Exception {
        validateVersion(targetVersion);
        String name = trim(requestedName);
        if (name.isEmpty()) {
            name = inspection.name();
        }
        validateName(name);
        String profile = inspection.defaultProfile();
        validateProfile(profile);
        String id = InstanceStore.newId();
        Path destination = data.instances().resolve(id);
        Path stagingBase = data.cache().resolve("pack-staging");
        Files.createDirectories(stagingBase);
        Path stage = Files.createTempDirectory(stagingBase, "job-");
        boolean registered = false;
        try {
            context.phase("解压整合包");
            context.log(describe(inspection));
            Path home = stage.resolve("dsh-home");
            Files.createDirectories(stage.resolve("workspace"));
            PackService packs = packs();
            packs.extract(inspection, home, context::log);
            context.checkCancelled();
            context.phase("安装运行时");
            RuntimeService runtime = runtimes.get();
            runtime.install(targetVersion, context::log);
            context.checkCancelled();
            Instance target = new Instance(id, name, targetVersion, profile, availablePort(), "", accountId);
            // 依赖恢复需要在最终路径进行（pnpm 可能写入绝对路径链接）。
            Files.move(stage, destination);
            if (!inspection.format().toLowerCase(Locale.ROOT).contains("snapshot")) {
                for (String unit : inspection.profiles()) {
                    context.checkCancelled();
                    runtime.restoreProfile(store.home(target), unit, store.workspace(target), true, context::log);
                }
            }
            context.checkCancelled();
            store.add(target);
            registered = true;
            audit.record("INSTANCE_IMPORTED", ip, "id=" + id + " version=" + targetVersion
                    + " profile=" + LogSanitizer.oneLine(profile, 64));
            context.log("整合包已安装到新实例：" + name + "。可在实例页启动。");
            logs.append("已导入实例：" + name + "（" + id + "）");
            return new ApiDtos.PackImportResultDto(id, name);
        } catch (Exception failure) {
            if (!registered && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Path kept = Trash.move(data, destination, "failed-import-" + id);
                    if (kept != null) {
                        context.log("导入未完成，本次文件保留在回收目录：" + kept.getFileName());
                    }
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        } finally {
            if (!registered) {
                deleteStaging(stage);
            }
        }
    }

    public String delete(String id, String ip) {
        jobs.ensureIdle();
        Instance instance = require(id);
        ensureStopped(instance);
        Path directory = store.directory(instance);
        Path trashed;
        try {
            trashed = Trash.move(data, directory, instance.id());
        } catch (IOException failure) {
            log.warn("实例目录移入回收目录失败：{}", failure.toString());
            throw ApiException.internal("实例目录移入回收目录失败，请检查数据目录权限。");
        }
        try {
            store.remove(id);
        } catch (IOException failure) {
            // 登记删除失败时把目录移回原位，避免清单与磁盘不一致。
            if (trashed != null) {
                try {
                    Files.move(trashed, directory);
                } catch (IOException restoreFailure) {
                    log.error("实例目录回滚失败，目录保留在 {}：{}", trashed, restoreFailure.toString());
                }
            }
            throw storeFailure(failure);
        }
        closeBinding(id);
        String trashedPath = trashed == null ? "" : trashed.toString();
        audit.record("INSTANCE_DELETED", ip, "id=" + id + " trashed=" + LogSanitizer.oneLine(trashedPath, 240));
        logs.append("实例已移入回收目录：" + id + (trashed == null ? "" : " → " + trashed));
        return trashedPath;
    }

    // ------------------------------------------------------------------ 选择 / 账户绑定

    public ApiDtos.InstanceDto select(String id, String ip) {
        jobs.ensureIdle();
        Instance instance = require(id);
        try {
            store.select(id);
        } catch (IOException failure) {
            throw storeFailure(failure);
        }
        audit.record("INSTANCE_SELECTED", ip, "id=" + id);
        return dto(instance);
    }

    public ApiDtos.InstanceDto bindAccount(String id, String accountId, String ip) {
        jobs.ensureIdle();
        Instance existing = require(id);
        ensureStopped(existing);
        String value = trim(accountId);
        validateAccount(value);
        Instance updated = new Instance(existing.id(), existing.name(), existing.runtimeVersion(), existing.profile(),
                existing.port(), existing.customCommand(), value);
        try {
            store.replace(updated);
        } catch (IOException failure) {
            throw storeFailure(failure);
        }
        audit.record("INSTANCE_ACCOUNT_BOUND", ip, "id=" + id
                + (value.isEmpty() ? " account=unbound" : " account=" + value));
        logs.append("实例账户已更新：" + id + (value.isEmpty() ? "（已解绑）" : "") + "，将在下次启动时生效。");
        return dto(updated);
    }

    // ------------------------------------------------------------------ 停止 / 工作台地址 / 启动 Job

    /** 停止实例；与桌面版一致，不受“忙任务”限制（运行中的实例必须随时可停）。 */
    public ApiDtos.InstanceDto stop(String id, String ip) {
        Instance instance = require(id);
        try {
            processes.stop(id, processLog);
        } catch (IOException failure) {
            throw ApiException.internal("停止实例失败：" + LogSanitizer.oneLine(failure.getMessage(), 200));
        }
        closeBinding(id);
        audit.record("INSTANCE_STOPPED", ip, "id=" + id);
        logs.append("已停止实例：" + instance.name() + "（" + id + "）");
        return dto(instance);
    }

    public String webUrl(String id) {
        Instance instance = require(id);
        if (!processes.running(id)) {
            throw ApiException.conflict(INSTANCE_NOT_READY, "请先启动当前实例。");
        }
        if (!"运行中".equals(processes.status(instance))) {
            throw ApiException.conflict(INSTANCE_NOT_READY, "网页服务尚未确认就绪，请等待启动完成或检查任务日志。");
        }
        URI address = processes.webAddress(instance);
        return address.toString();
    }

    /** POST /api/instances/{id}/launch：同步校验后提交 LAUNCH_INSTANCE 长任务（202 + Job）。 */
    public JobManager.View requestLaunch(String id, String ip) {
        Instance instance = require(id);
        if (processes.running(instance.id())) {
            throw ApiException.conflict(CONFLICT_INSTANCE_RUNNING, "实例已经运行，无需重复启动。");
        }
        if (!instance.customCommand().isBlank()) {
            throw customCommandFailure();
        }
        checkSingleRunning(instance);
        audit.record("INSTANCE_LAUNCH_REQUESTED", ip,
                "id=" + id + " version=" + instance.runtimeVersion() + " account="
                        + (instance.accountId().isBlank() ? "none" : "bound"));
        logs.append("请求启动实例：" + instance.name() + "（" + id + "）");
        return jobs.submit("LAUNCH_INSTANCE", LAUNCH_TIMEOUT, context -> launch(context, id));
    }

    /**
     * LAUNCH_INSTANCE 任务主体：移植 HDSL Controller.launch（安装→探测→组命令→环境→账户绑定→
     * 启动→账户校验→90 秒就绪）。取消/异常时停止本次创建的进程并清理账户临时文件。
     */
    private Object launch(JobManager.Context context, String id) throws Exception {
        Instance instance = require(id);
        if (processes.running(instance.id())) {
            throw ApiException.conflict(CONFLICT_INSTANCE_RUNNING, "实例已经运行，无需重复启动。");
        }
        if (!instance.customCommand().isBlank()) {
            throw customCommandFailure();
        }
        checkSingleRunning(instance);
        // 上次异常退出可能留下内存中的账户绑定（进程已不在），启动前清理。
        closeBinding(id);

        RuntimeService runtime = runtimes.get();
        context.checkCancelled();
        context.phase("准备运行时");
        if (runtime.installedVersions().contains(instance.runtimeVersion())) {
            context.log("运行时已安装：Harness " + instance.runtimeVersion() + "，跳过下载。");
        } else {
            context.phase("下载并安装运行时");
            context.log("尚未安装 Harness " + instance.runtimeVersion()
                    + "，将自动下载并安装；完成后继续启动，下次可复用已安装版本。");
            runtime.install(instance.runtimeVersion(), context::log);
        }
        context.checkCancelled();

        context.phase("检测启动参数");
        Capabilities capabilities = runtime.inspect(instance.runtimeVersion(), context::log);
        context.checkCancelled();
        List<String> command = runtime.launchCommand(instance.runtimeVersion(), instance.profile(),
                instance.port(), store.home(instance), store.workspace(instance), context::log);
        Map<String, String> environment = inheritedEnvironment();
        runtime.configureEnvironment(environment, store.home(instance), store.workspace(instance));

        LaunchBinding binding = null;
        try {
            if (!instance.accountId().isBlank()) {
                context.phase("绑定账户");
                binding = accounts.prepare(instance.accountId(), runtime.runtimeDirectory(instance.runtimeVersion()),
                        capabilities, store.directory(instance).resolve(".hdsl-account"));
                command = binding.appendTo(command);
                binding.applyEnvironment(environment);
                context.log("已准备账户注入：API Key 仅在本进程内存中使用，不写入磁盘或日志。");
            }
            context.checkCancelled();
            context.phase("启动实例进程");
            processes.start(instance, command, store.workspace(instance), environment, context::log);
            if (binding != null) {
                context.phase("校验账户绑定");
                binding.awaitValidation(BINDING_VALIDATION_TIMEOUT);
            }
            context.checkCancelled();
            if (binding != null) {
                // 校验通过后保留 overlay：进程运行期间可能重新读取该补丁文件。
                LaunchBinding previous = bindings.put(instance.id(), binding);
                binding = null;
                closeQuietly(previous);
            }
        } catch (Exception failure) {
            // 先清除中断标志，保证停止进程树与清理临时文件能完成。
            boolean interrupted = Thread.interrupted();
            try {
                processes.stop(instance.id(), context::log);
            } catch (IOException stopFailure) {
                failure.addSuppressed(stopFailure);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (binding != null && processes.running(instance.id())) {
                LaunchBinding previous = bindings.put(instance.id(), binding);
                binding = null;
                closeQuietly(previous);
            }
            throw failure;
        } finally {
            if (binding != null) {
                closeQuietly(binding);
            }
            environment.clear();
        }

        context.phase("等待网页服务就绪");
        boolean ready;
        try {
            ready = processes.awaitReady(instance, READY_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            try {
                processes.stop(instance.id(), context::log);
            } finally {
                closeBinding(instance.id());
            }
            throw new IOException("启动任务已取消。", interrupted);
        }
        if (!ready) {
            // 与桌面版一致：进程保留运行便于查看日志/手动停止，任务以 INSTANCE_NOT_READY 失败。
            context.log("进程仍在运行，但 90 秒内未确认网页服务就绪；可在实例页停止后重试。");
            throw ApiException.conflict(INSTANCE_NOT_READY,
                    "进程仍在运行，但 90 秒内未确认网页服务就绪；请查看任务日志，或停止实例后重试。");
        }
        URI address = processes.webAddress(instance);
        context.log("服务已就绪：" + address);
        return new ApiDtos.LaunchResultDto(instance.id(), address.toString());
    }

    // ------------------------------------------------------------------ 子资源复用

    /** 供插件等子资源接口复用：校验 ID 并返回实例（格式非法 400、不存在 404）。 */
    public Instance requireInstance(String id) {
        return require(id);
    }

    /** 供插件等写操作复用：实例必须已停止且端口可确认安全（运行中/端口占用时 409）。 */
    public void ensureStoppedForMutation(Instance instance) {
        ensureStopped(instance);
    }

    // ------------------------------------------------------------------ 内部工具

    private Instance require(String id) {
        String value = trim(id);
        try {
            InstanceStore.validateId(value);
        } catch (IOException failure) {
            throw ApiException.validation(failure.getMessage());
        }
        try {
            return store.find(value);
        } catch (IOException failure) {
            throw ApiException.notFound("实例不存在：" + value);
        }
    }

    private ApiDtos.InstanceDto dto(Instance instance) {
        return state.instance(instance);
    }

    private void ensureStopped(Instance instance) {
        if (processes.running(instance.id())) {
            throw ApiException.conflict(CONFLICT_INSTANCE_RUNNING, "请先停止实例，再修改其文件。");
        }
        if (!ProcessService.portFree(instance.port())) {
            throw ApiException.conflict(CONFLICT_PORT_BUSY, "实例端口正在被占用，无法确认可安全修改。");
        }
    }

    /** 单运行实例限制（方案 §4.3/§4.8）：除目标外的运行中实例存在时 409。 */
    private void checkSingleRunning(Instance target) {
        List<String> others = processes.runningIds().stream()
                .filter(running -> !running.equals(target.id()))
                .toList();
        if (others.isEmpty()) {
            return;
        }
        String names = others.stream().map(this::instanceName).collect(Collectors.joining("、"));
        throw ApiException.conflict(CONFLICT_RUNNING_INSTANCE,
                "服务器限制：同时最多运行 1 个实例。请先停止正在运行的实例（" + names + "）后再启动。");
    }

    private String instanceName(String id) {
        try {
            return store.find(id).name();
        } catch (IOException failure) {
            return id;
        }
    }

    private ApiException customCommandFailure() {
        return ApiException.conflict(CONFLICT_CUSTOM_COMMAND,
                "此实例保存了旧版自定义启动命令；Web 版出于安全考虑不执行自定义命令，请创建新实例或清除该字段后再启动。");
    }

    private void validateAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return;
        }
        try {
            boolean exists = accounts.list().stream().anyMatch(entry -> entry.id().equals(accountId));
            if (!exists) {
                throw ApiException.validation("所选账户不存在，请重新选择。");
            }
        } catch (IOException failure) {
            log.warn("读取账户列表失败：{}", failure.toString());
            throw ApiException.internal("读取账户列表失败，请检查数据目录权限。");
        }
    }

    private int portFor(String raw, Integer fallback) {
        String value = trim(raw);
        if (value.isEmpty() || "0".equals(value)) {
            return fallback != null ? fallback : availablePort();
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw ApiException.validation("端口必须为整数。");
        }
        if (port < 1024 || port > 65535) {
            throw ApiException.validation("端口须在 1024–65535 之间。");
        }
        return port;
    }

    /** 与桌面版 availablePort 一致：3080 起，避开清单端口并确认 OS 端口空闲。 */
    private int availablePort() {
        Set<Integer> used = new HashSet<>();
        for (Instance row : store.list()) {
            used.add(row.port());
        }
        for (int port = 3080; port <= 65535; port++) {
            if (!used.contains(port) && ProcessService.portFree(port)) {
                return port;
            }
        }
        throw ApiException.validation("没有可分配的实例端口，请手动指定 1024–65535 之间的空闲端口。");
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 160) {
            throw ApiException.validation("实例名称须为 1–160 个字符。");
        }
    }

    private static void validateVersion(String version) {
        try {
            InstanceStore.validateVersion(version);
        } catch (IOException failure) {
            throw ApiException.validation(failure.getMessage());
        }
    }

    private static void validateProfile(String profile) {
        try {
            InstanceStore.validateProfile(profile);
        } catch (IOException failure) {
            throw ApiException.validation(failure.getMessage());
        }
    }

    private ApiException storeFailure(IOException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("不存在")) {
            return ApiException.notFound(message);
        }
        if (message.startsWith("实例名称") || message.startsWith("端口须") || message.startsWith("实例 ID")
                || message.startsWith("端口已") || message.startsWith("Profile") || message.startsWith("请输入精确版本号")) {
            return ApiException.validation(message);
        }
        log.warn("实例数据写入失败：{}", failure.toString());
        return ApiException.internal("实例数据写入失败，请检查数据目录权限后重试。");
    }

    private void cleanupUnregistered(Path directory, String id) {
        try {
            Path trashed = Trash.move(data, directory, "unregistered-" + id);
            if (trashed != null) {
                log.warn("实例登记失败，目录已移入回收目录：{}", trashed);
            }
        } catch (IOException failure) {
            log.warn("未登记实例目录移入回收目录失败：{}", failure.toString());
        }
    }

    private void closeBinding(String id) {
        LaunchBinding binding = bindings.remove(id);
        if (binding != null) {
            closeQuietly(binding);
        }
    }

    private void closeQuietly(LaunchBinding binding) {
        if (binding == null) {
            return;
        }
        try {
            binding.close();
        } catch (IOException failure) {
            log.warn("账户启动临时文件清理未完成：{}", failure.toString());
            logs.append("账户启动临时文件清理未完成：" + LogSanitizer.oneLine(failure.getMessage(), 200));
        }
    }

    /** 继承服务器环境的白名单化：绝不让 PCL-DSH 主密钥或上一次启动的账户 Key 泄漏给子进程。 */
    private static Map<String, String> inheritedEnvironment() {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.keySet().removeIf(key -> key.equalsIgnoreCase("PCL_DSH_SECRET_KEY")
                || key.equalsIgnoreCase(LaunchBinding.KEY_ENV));
        return environment;
    }

    /** 按当前 settings.proxy 构造 PackService（构造很轻；导出/检查/解包共用）。 */
    private PackService packs() {
        String proxy = store.setting("proxy", "");
        try {
            return proxy == null || proxy.isBlank() ? new PackService() : new PackService(URI.create(proxy.trim()));
        } catch (IllegalArgumentException failure) {
            throw ApiException.internal("代理设置无效，无法处理整合包，请检查“设置”页面。");
        }
    }

    /** 与桌面版一致：web profile 且尚未初始化（没有 profiles/web/package.json）视为空白实例。 */
    private boolean isBlankWeb(Instance instance) {
        return "web".equals(instance.profile())
                && !Files.isRegularFile(store.home(instance).resolve("profiles/web/package.json"));
    }

    private static String describe(PackInspection inspection) {
        return "名称：" + inspection.name() + " · 格式：" + inspection.format() + " · 包版本：" + inspection.version()
                + " · Harness：" + (inspection.dshVersion() == null ? "未声明" : inspection.dshVersion())
                + " · Profile：" + String.join(", ", inspection.profiles())
                + " · 解压约：" + String.format(Locale.ROOT, "%.1f MiB", inspection.unpackedBytes() / 1048576d);
    }

    /** 导出包名：小写 kebab-case；非 kebab 的实例名回退为 hdsl-<id>（与桌面版一致）。 */
    private static String packName(Instance instance) {
        if (instance.name().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            return instance.name();
        }
        String id = instance.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return "hdsl-" + id;
    }

    /** 暂存目录清理：只允许删除 data/cache 下的路径。 */
    private void deleteStaging(Path folder) {
        if (folder == null) {
            return;
        }
        Path cache = data.cache().toAbsolutePath().normalize();
        Path normalized = folder.toAbsolutePath().normalize();
        if (!normalized.startsWith(cache) || normalized.equals(cache)
                || !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            log.warn("暂存目录清理未完成：{}", failure.toString());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 服务关闭：释放账户 overlay 临时文件（进程由 ProcessService 的 destroyMethod 关闭）。 */
    @PreDestroy
    public void closeAccountBindings() {
        for (String id : List.copyOf(bindings.keySet())) {
            closeBinding(id);
        }
    }
}
