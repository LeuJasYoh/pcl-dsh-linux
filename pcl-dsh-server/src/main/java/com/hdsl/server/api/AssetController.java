package com.hdsl.server.api;

import com.hdsl.server.asset.BackgroundService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 背景资源读取（方案 §4.7）：仅允许 data/assets/backgrounds 下的安全文件名。
 * 需要登录（同源 &lt;img&gt; 自动携带会话 Cookie）；不缓存为公共资源。
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final BackgroundService backgrounds;

    public AssetController(BackgroundService backgrounds) {
        this.backgrounds = backgrounds;
    }

    @GetMapping("/backgrounds/{name}")
    public ResponseEntity<Resource> background(@PathVariable("name") String name) {
        BackgroundService.BackgroundFile file = backgrounds.resolve(name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.path()));
    }
}
