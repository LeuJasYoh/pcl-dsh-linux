package com.hdsl.server.api;

import com.hdsl.server.job.JobManager;
import com.hdsl.server.pack.PackApiService;
import com.hdsl.server.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 整合包接口（方案 §4.2 / §4.7）：
 * <ul>
 *   <li>POST /api/packs/preview：multipart "file"（≤2 GiB，流式落盘）→ 200 {previewId,...}（30 分钟有效）</li>
 *   <li>POST /api/packs/import：{previewId,name?,version?} → 202 Job（IMPORT_PACK 30m，result={instanceId,name}）</li>
 *   <li>POST /api/packs/export：{instanceId,mode} → 202 Job（EXPORT_PACK 10m，result={artifactId,filename,size}）</li>
 * </ul>
 * 所有写接口需要登录会话 + CSRF；有任务在跑时 409 CONFLICT_BUSY。
 */
@RestController
@RequestMapping("/api/packs")
public class PackController {

    private final PackApiService packs;

    public PackController(PackApiService packs) {
        this.packs = packs;
    }

    @PostMapping("/preview")
    public ApiDtos.PackPreviewDto preview(
            @RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest request) {
        return packs.preview(file, ClientIp.of(request));
    }

    @PostMapping("/import")
    public ResponseEntity<JobManager.View> importPack(
            @RequestBody(required = false) ApiDtos.PackImportRequest body, HttpServletRequest request) {
        return ResponseEntity.accepted().body(packs.importPack(body, ClientIp.of(request)));
    }

    @PostMapping("/export")
    public ResponseEntity<JobManager.View> exportPack(
            @RequestBody(required = false) ApiDtos.PackExportRequest body, HttpServletRequest request) {
        return ResponseEntity.accepted().body(packs.exportPack(body, ClientIp.of(request)));
    }
}
