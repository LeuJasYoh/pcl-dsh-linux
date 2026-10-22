package com.hdsl.server.api;

import com.hdsl.server.job.JobManager;
import com.hdsl.server.plugin.PluginApiService;
import com.hdsl.server.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实例插件接口（方案 §4.2 / M3 预留）：列表读取 + 添加/更新/移除长任务。
 * 变更操作要求实例已停止；长任务返回 202 + JobManager.View（jobId=body.id，type=PLUGIN_MUTATE）。
 */
@RestController
@RequestMapping("/api/instances")
public class PluginController {

    private final PluginApiService plugins;

    public PluginController(PluginApiService plugins) {
        this.plugins = plugins;
    }

    @GetMapping("/{id}/plugins")
    public List<ApiDtos.PluginDto> list(@PathVariable("id") String id,
                                        @RequestParam(value = "profile", required = false) String profile) {
        return plugins.list(id, profile);
    }

    @PostMapping("/{id}/plugins")
    public ResponseEntity<JobManager.View> add(@PathVariable("id") String id,
                                               @RequestBody(required = false) ApiDtos.PluginMutationRequest body,
                                               HttpServletRequest request) {
        String spec = body == null ? null : body.spec();
        return ResponseEntity.accepted().body(plugins.mutate(id, "add", spec, ClientIp.of(request)));
    }

    /** 支持 scoped 包名（@scope/name）：catch-all 路径变量，去掉前导斜杠后交由服务层校验。 */
    @PatchMapping("/{id}/plugins/{*name}")
    public ResponseEntity<JobManager.View> update(@PathVariable("id") String id,
                                                  @PathVariable("name") String name,
                                                  HttpServletRequest request) {
        return ResponseEntity.accepted().body(plugins.mutate(id, "update", normalize(name), ClientIp.of(request)));
    }

    /** 支持 scoped 包名（@scope/name），语义同 PATCH。 */
    @DeleteMapping("/{id}/plugins/{*name}")
    public ResponseEntity<JobManager.View> remove(@PathVariable("id") String id,
                                                  @PathVariable("name") String name,
                                                  HttpServletRequest request) {
        return ResponseEntity.accepted().body(plugins.mutate(id, "remove", normalize(name), ClientIp.of(request)));
    }

    private static String normalize(String name) {
        String value = name == null ? "" : name.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
