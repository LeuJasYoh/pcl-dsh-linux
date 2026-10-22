package com.hdsl.server.util;

import com.hdsl.server.config.DataRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 删除语义与桌面版一致：不物理删除，而是移入 data/trash（方案 §4.2 / HDSL Controller.moveToTrash）。
 * 只允许移动数据根目录内的路径，移到根目录本身或越界路径一律拒绝。
 */
public final class Trash {

    private Trash() {
    }

    /**
     * @param source 待移动的数据根内路径
     * @param name   回收目录内的名称前缀（实例 ID / runtime-版本），会做文件名安全化
     * @return 目标路径；源不存在时返回 null
     */
    public static Path move(DataRoot data, Path source, String name) throws IOException {
        Path root = data.root().toAbsolutePath().normalize();
        Path normalized = source.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("目标越出数据目录。");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Files.createDirectories(data.trash());
        String prefix = safeName(name);
        Path destination = data.trash().resolve(prefix + "-" + System.currentTimeMillis());
        int attempt = 0;
        while (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            destination = data.trash().resolve(prefix + "-" + System.currentTimeMillis() + "-" + (++attempt));
        }
        Files.move(normalized, destination);
        return destination;
    }

    private static String safeName(String name) {
        String value = name == null || name.isBlank() ? "item" : name.strip();
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return value.length() > 96 ? value.substring(0, 96) : value;
    }
}
