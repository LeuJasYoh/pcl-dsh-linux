package com.hdsl.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动自检：Java 版本、数据目录可写（由 {@link DataRoot} 完成）、Node/pnpm 可用性告警。
 * Java 版本或数据目录不满足条件时给出中文可操作错误并终止启动。
 */
@Component
public class StartupChecks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final DataRoot data;
    private final PclProperties properties;

    public StartupChecks(DataRoot data, PclProperties properties) {
        this.data = data;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int feature = Runtime.version().feature();
        if (feature < 21) {
            throw new IllegalStateException("PCL-DSH-Linux 需要 Java 21 或更高版本，当前检测到 Java " + feature
                    + "。请安装 JDK 21，并通过 JAVA_HOME / systemd 的 ExecStart 指向该 JDK 后重新启动。");
        }
        log.info("PCL-DSH-Linux {} 启动自检：Java {}，数据目录 {}，会话超时 {}",
                properties.getVersion(), Runtime.version(), data.root(), properties.getSessionTimeout());
        Path releaseTools = releaseTools();
        ToolLocator.findNode(data.root(), releaseTools).ifPresentOrElse(
                path -> log.info("Node.js：{}", path),
                () -> log.warn("未找到 Node.js（查找顺序：发布包 tools、data/tools、系统 PATH）。运行时安装与实例启动将失败；"
                        + "请确认发布包携带 tools/node，或加入服务运行用户的 PATH。"));
        ToolLocator.findPnpm(data.root(), releaseTools).ifPresentOrElse(
                path -> log.info("pnpm：{}", path),
                () -> log.warn("未找到 pnpm（查找顺序：发布包 tools、data/tools、系统 PATH）。安装/恢复运行时依赖将失败；"
                        + "请确认发布包携带 tools/pnpm，或加入服务运行用户的 PATH。"));
        log.info("启动自检完成：数据目录可写，Java 版本满足要求。");
    }

    /** 发布包只读 tools：优先 pcl.tool-root，其次从 jar 所在目录的 tools 推断。 */
    private Path releaseTools() {
        String configured = properties.getToolRoot();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        try {
            var source = StartupChecks.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI());
                Path base = Files.isDirectory(location) ? location : location.getParent();
                if (base != null) {
                    Path tools = base.resolve("tools");
                    if (Files.isDirectory(tools)) {
                        return tools;
                    }
                }
            }
        } catch (Exception ignored) {
            // 推断失败时仅告警 PID 路径问题由运维处理。
        }
        return null;
    }
}
