package com.hdsl.server.config;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 启动自检用的 Node.js / pnpm 查找（与 pcl-dsh-core 的查找顺序保持一致：
 * data/tools 优先，其次系统 PATH）。只做“是否存在”告警，不阻断启动。
 */
final class ToolLocator {

    private ToolLocator() {
    }

    static Optional<Path> findNode(Path dataRoot) {
        Path tools = dataRoot.resolve("tools");
        for (Path candidate : new Path[]{
                tools.resolve("node/node.exe"),
                tools.resolve("node/bin/node"),
                tools.resolve("node/node")}) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return onPath("node.exe", "node");
    }

    static Optional<Path> findPnpm(Path dataRoot) {
        Path tools = dataRoot.resolve("tools");
        for (Path candidate : new Path[]{
                tools.resolve("pnpm/bin/pnpm.cjs"),
                tools.resolve("pnpm/node_modules/pnpm/bin/pnpm.cjs"),
                tools.resolve("node/node_modules/pnpm/bin/pnpm.cjs")}) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return onPath("pnpm.cmd", "pnpm");
    }

    private static Optional<Path> onPath(String... names) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Path.of(entry).resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (RuntimeException ignored) {
                    // 非法 PATH 项直接跳过
                }
            }
        }
        return Optional.empty();
    }
}
