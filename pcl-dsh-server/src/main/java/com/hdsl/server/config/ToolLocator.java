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

    static Optional<Path> findNode(Path dataRoot, Path releaseTools) {
        Optional<Path> fromTools = findInToolRoots(releaseTools, dataRoot.resolve("tools"),
                "node/node.exe", "node/bin/node", "node/node");
        return fromTools.isPresent() ? fromTools : onPath("node.exe", "node");
    }

    static Optional<Path> findPnpm(Path dataRoot, Path releaseTools) {
        Optional<Path> fromTools = findInToolRoots(releaseTools, dataRoot.resolve("tools"),
                "pnpm/bin/pnpm.cjs", "pnpm/node_modules/pnpm/bin/pnpm.cjs", "node/node_modules/pnpm/bin/pnpm.cjs");
        return fromTools.isPresent() ? fromTools : onPath("pnpm.cmd", "pnpm");
    }

    private static Optional<Path> findInToolRoots(Path releaseTools, Path dataTools, String... candidates) {
        for (Path root : new Path[]{releaseTools, dataTools}) {
            if (root == null) {
                continue;
            }
            for (String relative : candidates) {
                Path candidate = root.resolve(relative);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
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
