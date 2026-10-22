package com.hdsl.server.api;

import com.hdsl.server.artifact.ArtifactStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 产物下载（方案 §4.7，供后续整合包导出使用）：流式返回 data/cache/exports 下登记的文件。
 * 未知或已过期（24 小时）的 id 返回 404；响应含安全文件名 + no-store。
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactStore artifacts;

    public ArtifactController(ArtifactStore artifacts) {
        this.artifacts = artifacts;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable("id") String id) {
        ArtifactStore.Artifact artifact = artifacts.find(id);
        if (artifact == null) {
            throw ApiException.notFound("下载链接不存在或已过期（产物保留 24 小时）。");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .contentLength(artifact.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(artifact.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(artifact.path()));
    }
}
