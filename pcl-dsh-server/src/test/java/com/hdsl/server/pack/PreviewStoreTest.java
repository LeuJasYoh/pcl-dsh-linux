package com.hdsl.server.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdsl.pack.PackInspection;
import com.hdsl.server.config.DataRoot;
import com.hdsl.server.config.PclProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PreviewStore：启动清理上次遗留上传文件；正常预览不会被定时清理误删。 */
class PreviewStoreTest {

    private Path root;
    private DataRoot data;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("pcl-preview-test-");
        PclProperties properties = new PclProperties();
        properties.setDataRoot(root);
        data = new DataRoot(properties);
        data.initialize();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void startupRemovesOrphanUploadsFromPreviousRun() throws IOException {
        Path uploads = data.cache().resolve("uploads");
        Files.createDirectories(uploads);
        Files.writeString(uploads.resolve("orphan.zip"), "leftover", StandardCharsets.UTF_8);

        PreviewStore store = new PreviewStore(data);
        store.initialize();

        try (Stream<Path> files = Files.list(uploads)) {
            assertEquals(0, files.filter(Files::isRegularFile).count(),
                    "重启后无登记的上传文件必须被清理");
        }
    }

    @Test
    void registeredPreviewSurvivesSweep() throws IOException {
        PreviewStore store = new PreviewStore(data);
        store.initialize();
        Path archive = store.allocate();
        Files.writeString(archive, "zip-bytes", StandardCharsets.UTF_8);
        PackInspection inspection = new PackInspection(archive, "dspack", "test-pack", "1.0.0",
                "0.1.0-rc.6", "web", List.of("web"), List.of(), 9L,
                new ObjectMapper().createObjectNode(), "sha256");
        PreviewStore.Preview preview = store.register(archive, "sha256", inspection,
                Files.size(archive));
        assertTrue(preview.id().startsWith("p-"));
        assertNotNull(store.find(preview.id()));
        assertEquals(0, store.sweepExpired(), "未过期预览不得被定时任务清理");
        assertNotNull(store.find(preview.id()));
        store.remove(preview.id());
        assertEquals(null, store.find(preview.id()));
    }
}
