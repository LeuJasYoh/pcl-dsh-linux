package com.hdsl.server.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据根目录及其标准子目录。启动时创建目录并验证可写性；
 * 不可写时以中文可操作信息终止启动（便于 systemd 重启与排障）。
 */
@Component
public class DataRoot {

    private static final Logger log = LoggerFactory.getLogger(DataRoot.class);

    private final Path root;
    private final Path config;
    private final Path logs;
    private final Path instances;
    private final Path runtimes;
    private final Path cache;
    private final Path assets;
    private final Path tools;
    private final Path trash;
    private final Path backups;

    public DataRoot(PclProperties properties) {
        this.root = properties.getDataRoot().toAbsolutePath().normalize();
        this.config = root.resolve("config");
        this.logs = root.resolve("logs");
        this.instances = root.resolve("instances");
        this.runtimes = root.resolve("runtimes");
        this.cache = root.resolve("cache");
        this.assets = root.resolve("assets");
        this.tools = root.resolve("tools");
        this.trash = root.resolve("trash");
        this.backups = root.resolve("backups");
    }

    @PostConstruct
    public void initialize() {
        List<Path> directories = new ArrayList<>(List.of(
                root, config, logs, instances, runtimes, cache, assets, tools, trash, backups));
        for (String relative : List.of(
                "assets/backgrounds", "cache/pnpm-store", "cache/probes", "cache/npm",
                "cache/pack-staging", "cache/uploads", "cache/exports", "tools/shims")) {
            directories.add(root.resolve(relative));
        }
        try {
            for (Path directory : directories) {
                Files.createDirectories(directory);
            }
            Path probe = Files.createTempFile(root, ".pcl-write-probe-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            throw new IllegalStateException("数据目录不可用：" + root
                    + "。请确认该目录存在且运行用户可读写（例如 chown -R <运行用户> " + root
                    + " && chmod 700 " + root + "），或通过环境变量 PCL_DSH_DATA / 启动参数 -Dpcl.data 指定可写目录。原因："
                    + e.getMessage(), e);
        }
        try {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / 非 POSIX 文件系统：权限设置尽力而为，不阻断启动。
        }
        log.info("数据目录：{}", root);
    }

    public Path root() {
        return root;
    }

    public Path config() {
        return config;
    }

    public Path logs() {
        return logs;
    }

    public Path instances() {
        return instances;
    }

    public Path runtimes() {
        return runtimes;
    }

    public Path cache() {
        return cache;
    }

    public Path assets() {
        return assets;
    }

    public Path tools() {
        return tools;
    }

    public Path trash() {
        return trash;
    }

    public Path backups() {
        return backups;
    }
}
