package com.hdsl.server.api;

import com.hdsl.server.job.JobManager;
import com.hdsl.server.runtime.RuntimeTaskService;
import com.hdsl.server.security.ClientIp;
import com.hdsl.server.state.StateAssembler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 运行时接口（方案 §4.2）：已安装列表、可用版本查询（60 秒）、安装/探测长任务、删除。
 * 长任务统一返回 202 + JobManager.View（jobId=body.id）。
 */
@RestController
@RequestMapping("/api")
public class RuntimeController {

    private final StateAssembler state;
    private final RuntimeTaskService tasks;

    public RuntimeController(StateAssembler state, RuntimeTaskService tasks) {
        this.state = state;
        this.tasks = tasks;
    }

    @GetMapping("/runtimes")
    public List<ApiDtos.RuntimeDto> runtimes() {
        try {
            return state.runtimes();
        } catch (IOException e) {
            throw ApiException.internal("读取已安装运行时失败：" + e.getMessage());
        }
    }

    /** 可用版本查询：发现类任务，不占用全局变更锁；上游异常 502/504（中文）。 */
    @GetMapping("/runtimes/available")
    public ApiDtos.AvailableVersionsDto available() {
        return new ApiDtos.AvailableVersionsDto(tasks.availableVersions());
    }

    @PostMapping("/runtimes")
    public ResponseEntity<JobManager.View> install(
            @RequestBody(required = false) ApiDtos.RuntimeInstallRequest body, HttpServletRequest request) {
        if (body == null) {
            throw ApiException.validation("请输入要安装的运行时版本号。");
        }
        return ResponseEntity.accepted().body(tasks.install(body.version(), ClientIp.of(request)));
    }

    @PostMapping("/runtimes/{version}/probe")
    public ResponseEntity<JobManager.View> probe(@PathVariable("version") String version,
                                                 HttpServletRequest request) {
        return ResponseEntity.accepted().body(tasks.probe(version, ClientIp.of(request)));
    }

    @DeleteMapping("/runtimes/{version}")
    public ApiDtos.TrashedDto delete(@PathVariable("version") String version, HttpServletRequest request) {
        return new ApiDtos.TrashedDto(tasks.delete(version, ClientIp.of(request)));
    }
}
