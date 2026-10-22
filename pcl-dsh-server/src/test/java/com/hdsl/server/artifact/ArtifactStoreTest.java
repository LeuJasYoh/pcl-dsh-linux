package com.hdsl.server.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ArtifactStore：元数据持久化（重启后仍可下载）、过期/缺失清理、路径白名单与损坏元数据容错。 */
class ArtifactStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path root;
    private DataRoot data;
    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("pcl-artifact-test-");
        PclProperties properties = new PclProperties();
        properties.setDataRoot(root);
        data = new DataRoot(properties);
        data.initialize();
        store = new ArtifactStore(data);
        store.initialize();
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
    void registeredArtifactSurvivesRestart() throws IOException {
        Path file = exportFile("sample.dspack", "content");
        String id = store.register(file, "测试包.dspack", "application/zip");
        assertTrue(Files.isRegularFile(store.metadataFile()));

        ArtifactStore reloaded = new ArtifactStore(data);
        reloaded.initialize();
        ArtifactStore.Artifact artifact = reloaded.find(id);
        assertNotNull(artifact, "重启后旧 artifact 链接必须仍可下载");
        assertEquals("测试包.dspack", artifact.filename());
        assertEquals("application/zip", artifact.contentType());
        assertEquals(file.toAbsolutePath().normalize(), artifact.path());
        assertEquals(Files.size(file), artifact.size());
        assertTrue(artifact.expiresAt() > artifact.createdAt());
    }

    @Test
    void expiredArtifactIsRemovedOnStartup() throws IOException {
        Path file = exportFile("expired.dspack", "old");
        String id = store.register(file, "expired.dspack", "application/octet-stream");
        JsonNode metadata = JSON.readTree(store.metadataFile().toFile());
        ObjectNode entry = (ObjectNode) metadata.path("artifacts").get(0);
        entry.put("expiresAt", System.currentTimeMillis() - 1000L);
        JSON.writeValue(store.metadataFile().toFile(), metadata);

        ArtifactStore reloaded = new ArtifactStore(data);
        reloaded.initialize();
        assertNull(reloaded.find(id));
        assertFalse(Files.exists(file), "过期产物文件必须被删除");
        JsonNode after = JSON.readTree(reloaded.metadataFile().toFile());
        assertEquals(0, after.path("artifacts").size(), "过期记录必须从元数据移除");
    }

    @Test
    void missingFileRemovesRecordAfterRestart() throws IOException {
        Path file = exportFile("missing.dspack", "gone");
        String id = store.register(file, "missing.dspack", "application/zip");
        Files.delete(file);

        ArtifactStore reloaded = new ArtifactStore(data);
        reloaded.initialize();
        assertNull(reloaded.find(id));
        JsonNode after = JSON.readTree(reloaded.metadataFile().toFile());
        assertEquals(0, after.path("artifacts").size());
    }

    @Test
    void sweepExpiredKeepsFreshArtifacts() throws IOException {
        Path file = exportFile("fresh.dspack", "fresh");
        String id = store.register(file, "fresh.dspack", "application/zip");
        assertEquals(0, store.sweepExpired());
        assertNotNull(store.find(id));
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void rejectsFilesOutsideExports() throws IOException {
        Path outside = data.cache().resolve("outside.bin");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "x", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> store.register(outside, "outside.bin", "application/octet-stream"));
        assertThrows(IllegalArgumentException.class,
                () -> store.register(data.cache().resolve("exports/missing.bin"), "missing.bin",
                        "application/octet-stream"));
    }

    @Test
    void corruptMetadataFallsBackToEmptyAndStaysUsable() throws IOException {
        Files.createDirectories(data.cache().resolve("exports"));
        Files.writeString(store.metadataFile(), "{ broken json", StandardCharsets.UTF_8);

        ArtifactStore reloaded = new ArtifactStore(data);
        reloaded.initialize();
        assertNull(reloaded.find("0123456789abcdef0123456789abcdef"));

        Path file = exportFile("after-corrupt.dspack", "ok");
        String id = reloaded.register(file, "after-corrupt.dspack", "application/zip");
        assertNotNull(reloaded.find(id), "损坏元数据后仍应能继续登记新产物");
        assertTrue(Files.isRegularFile(reloaded.metadataFile()));
    }

    private Path exportFile(String name, String content) throws IOException {
        Path exports = data.cache().resolve("exports");
        Files.createDirectories(exports);
        Path file = exports.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
