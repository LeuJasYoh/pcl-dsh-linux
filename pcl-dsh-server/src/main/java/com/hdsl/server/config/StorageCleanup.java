package com.hdsl.server.config;

import com.hdsl.server.artifact.ArtifactStore;
import com.hdsl.server.pack.PreviewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 存储过期清理定时任务：每 5 分钟清理一次过期的整合包预览与导出产物。
 * 启动时两个 Store 各自在 {@code @PostConstruct} 完成首次清理；此任务只处理运行期过期项，
 * 不会删除未过期内容，因此不影响进行中的任务与测试。
 */
@Component
public class StorageCleanup {

    private static final Logger log = LoggerFactory.getLogger(StorageCleanup.class);

    private final PreviewStore previews;
    private final ArtifactStore artifacts;

    public StorageCleanup(PreviewStore previews, ArtifactStore artifacts) {
        this.previews = previews;
        this.artifacts = artifacts;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void cleanupExpired() {
        try {
            previews.sweepExpired();
            artifacts.sweepExpired();
        } catch (RuntimeException failure) {
            log.warn("过期存储清理任务异常（下次重试）：{}", failure.toString());
        }
    }
}
