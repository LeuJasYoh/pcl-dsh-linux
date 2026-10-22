package com.hdsl.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试专用数据目录：位于 target/test-data 下，每次运行唯一，避免污染 ./data 与相互干扰。 */
final class TestDataRoots {

    private TestDataRoots() {
    }

    static Path prepare(String name) {
        try {
            Path base = Path.of("target", "test-data").toAbsolutePath().normalize();
            Files.createDirectories(base);
            return Files.createTempDirectory(base, name + "-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试数据目录", e);
        }
    }
}
