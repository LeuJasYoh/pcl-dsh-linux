package com.hdsl.server.api;

import com.hdsl.server.instance.InstanceService;
import com.hdsl.server.job.JobManager;
import com.hdsl.server.security.ClientIp;
import com.hdsl.server.state.StateAssembler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实例接口（方案 §4.2）：读取、CRUD、选择、账户绑定、启停与启动长任务。
 *
 * <p>Controller 只做 HTTP 层参数绑定与调用 {@link InstanceService}；业务规则、中文错误映射
 * 与长任务编排都在 service，底层复用 pcl-dsh-core。所有写接口需要登录会话 + CSRF。</p>
 *
 * <p>按 Web 安全要求（方案 §5.5 第 5 条），不提供 customCommand 入口，非空 customCommand
 * 实例启动时明确拒绝（409）。</p>
 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final StateAssembler state;
    private final InstanceService instances;

    public InstanceController(StateAssembler state, InstanceService instances) {
        this.state = state;
        this.instances = instances;
    }

    @GetMapping
    public List<ApiDtos.InstanceDto> list() {
        return state.instances();
    }

    @GetMapping("/{id}")
    public ApiDtos.InstanceDto get(@PathVariable("id") String id) {
        return instances.get(id);
    }

    @PostMapping
    public ResponseEntity<ApiDtos.InstanceDto> create(
            @RequestBody(required = false) ApiDtos.CreateInstanceRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(instances.create(body, ClientIp.of(request)));
    }

    /**
     * 普通修改（name/port/accountId）→ 200 InstanceDto；
     * version/profile 变更 → 202 Job（type=COPY_INSTANCE，复制为新实例，原实例保留）。
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String id,
                                    @RequestBody(required = false) ApiDtos.UpdateInstanceRequest body,
                                    HttpServletRequest request) {
        InstanceService.UpdateOutcome outcome = instances.update(id, body, ClientIp.of(request));
        if (outcome.job() != null) {
            return ResponseEntity.accepted().body(outcome.job());
        }
        return ResponseEntity.ok(outcome.instance());
    }

    @DeleteMapping("/{id}")
    public ApiDtos.TrashedDto delete(@PathVariable("id") String id, HttpServletRequest request) {
        return new ApiDtos.TrashedDto(instances.delete(id, ClientIp.of(request)));
    }

    @PutMapping("/{id}/selection")
    public ApiDtos.InstanceDto select(@PathVariable("id") String id, HttpServletRequest request) {
        return instances.select(id, ClientIp.of(request));
    }

    @PutMapping("/{id}/account")
    public ApiDtos.InstanceDto bindAccount(@PathVariable("id") String id,
                                           @RequestBody(required = false) ApiDtos.AccountBindingRequest body,
                                           HttpServletRequest request) {
        return instances.bindAccount(id, body == null ? "" : body.accountId(), ClientIp.of(request));
    }

    @PostMapping("/{id}/stop")
    public ApiDtos.InstanceDto stop(@PathVariable("id") String id, HttpServletRequest request) {
        return instances.stop(id, ClientIp.of(request));
    }

    @GetMapping("/{id}/web-url")
    public ApiDtos.WebUrlDto webUrl(@PathVariable("id") String id) {
        return new ApiDtos.WebUrlDto(instances.webUrl(id));
    }

    /**
     * 远程账号网页登录所需的直连信息：回环直连地址（含一次性 token）与 SSH 隧道命令。
     *
     * <p>上游 DSH 的 DeepSeek 账号 OAuth 回调仅接受回环地址；远程部署时需按此提示建立
     * SSH 隧道，在本机浏览器打开直连地址完成一次登录，凭据保存在服务器实例目录。</p>
     */
    @GetMapping("/{id}/direct-access")
    public ApiDtos.DirectAccessDto directAccess(@PathVariable("id") String id, HttpServletRequest request) {
        return instances.directAccess(id, request.getServerName());
    }

    /** 202 + JobManager.View（body.id 为 jobId；type=LAUNCH_INSTANCE；成功 result={instanceId,webUrl}）。 */
    @PostMapping("/{id}/launch")
    public ResponseEntity<JobManager.View> launch(@PathVariable("id") String id, HttpServletRequest request) {
        return ResponseEntity.accepted().body(instances.requestLaunch(id, ClientIp.of(request)));
    }

    /** 202 + Job（type=COPY_INSTANCE；成功 result={instanceId,name}；原实例保留）。 */
    @PostMapping("/{id}/copy")
    public ResponseEntity<JobManager.View> copy(@PathVariable("id") String id,
                                                @RequestBody(required = false) ApiDtos.CopyInstanceRequest body,
                                                HttpServletRequest request) {
        String name = body == null ? null : body.name();
        return ResponseEntity.accepted().body(instances.requestCopy(id, name, ClientIp.of(request)));
    }
}
