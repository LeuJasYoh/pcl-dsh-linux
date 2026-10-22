package com.hdsl.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hdsl.server.api.ApiException;
import com.hdsl.server.config.DataRoot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * 单管理员密码存储：data/config/server.json（schemaVersion=1，BCrypt 哈希）。
 *
 * <p>首次启动生成随机强初始密码，仅写入 data/initial-password（尽量 0600），
 * 日志只提示文件路径，绝不打印密码；首次登录后必须先改密。</p>
 */
@Component
public class ServerCredentials {

    private static final Logger log = LoggerFactory.getLogger(ServerCredentials.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 避免容易混淆的字符，并排除 shell 引号敏感字符，便于从文件复制粘贴。 */
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%^&*-_=+";
    private static final int PASSWORD_LENGTH = 24;

    private final DataRoot data;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Object writeLock = new Object();

    private volatile String passwordHash;
    private volatile boolean initialPasswordPending;
    private volatile Instant createdAt;

    public ServerCredentials(DataRoot data) {
        this.data = data;
    }

    @PostConstruct
    public void initialize() {
        Path file = serverFile();
        try {
            if (Files.isRegularFile(file)) {
                load(file);
            } else {
                create(file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化服务器凭据（" + file + "）：" + e.getMessage()
                    + "。请确认数据目录可写；如需重置管理员密码，请先备份并删除该文件后重启"
                    + "（会重新生成 data/initial-password）。", e);
        }
    }

    public Path serverFile() {
        return data.config().resolve("server.json");
    }

    public Path initialPasswordFile() {
        return data.root().resolve("initial-password");
    }

    public boolean initialPasswordPending() {
        return initialPasswordPending;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** BCrypt 校验；失败返回 false，不抛出也不记录明文。 */
    public boolean verify(String rawPassword) {
        String hash = passwordHash;
        if (rawPassword == null || rawPassword.isEmpty() || hash == null) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 修改管理员密码：先校验原密码，再校验新密码强度，最后原子写入 server.json。
     * 修改成功后删除初始密码文件，避免长期留存。
     */
    public synchronized void changePassword(String oldPassword, String newPassword) {
        if (!verify(oldPassword)) {
            throw ApiException.validation("原密码不正确。");
        }
        if (newPassword == null || newPassword.isEmpty()) {
            throw ApiException.validation("请输入新密码。");
        }
        if (newPassword.length() < 8 || newPassword.length() > 128) {
            throw ApiException.validation("新密码长度须为 8–128 个字符。");
        }
        if (newPassword.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.validation("新密码不能包含换行或控制字符。");
        }
        if (newPassword.equals(oldPassword)) {
            throw ApiException.validation("新密码不能与原密码相同。");
        }
        String newHash = encoder.encode(newPassword);
        synchronized (writeLock) {
            try {
                writeServerJson(newHash, false);
            } catch (IOException e) {
                throw new IllegalStateException("密码保存失败，请检查数据目录权限后重试。", e);
            }
            passwordHash = newHash;
            initialPasswordPending = false;
            try {
                if (Files.deleteIfExists(initialPasswordFile())) {
                    log.info("初始密码文件已删除：{}", initialPasswordFile());
                }
            } catch (IOException e) {
                log.warn("初始密码文件删除失败（不影响登录）：{}", e.toString());
            }
            log.info("管理员密码已修改。");
        }
    }

    private void load(Path file) throws IOException {
        JsonNode node = JSON.readTree(file.toFile());
        if (node == null || node.path("schemaVersion").asInt(-1) != 1
                || node.path("passwordHash").asText("").isBlank()) {
            throw new IOException("server.json 格式不受支持（需要 schemaVersion=1 与 passwordHash）");
        }
        passwordHash = node.path("passwordHash").asText();
        initialPasswordPending = node.path("initialPasswordPending").asBoolean(false);
        String created = node.path("createdAt").asText("");
        try {
            createdAt = created.isBlank() ? Instant.now() : Instant.parse(created);
        } catch (RuntimeException e) {
            createdAt = Instant.now();
        }
        if (initialPasswordPending) {
            log.warn("首次登录尚未修改初始密码。若忘记初始密码，请查看 {} 或通过服务器端重置流程处理。",
                    initialPasswordFile());
        }
    }

    private void create(Path file) throws IOException {
        String password = generatePassword();
        String hash = encoder.encode(password);
        synchronized (writeLock) {
            // 先写密码文件再写 server.json：中途失败时下次启动会重新生成，不会出现“有哈希但无初始密码”的死锁。
            writeSecure(initialPasswordFile(), password + System.lineSeparator());
            writeServerJson(hash, true);
            passwordHash = hash;
            initialPasswordPending = true;
            createdAt = Instant.now();
        }
        log.warn("首次启动：已生成随机初始管理员密码，仅写入文件 {}（权限已尽力设为 0600）。"
                + "请登录后立即修改密码；该密码不会出现在日志中。", initialPasswordFile());
    }

    private void writeServerJson(String hash, boolean pending) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("schemaVersion", 1);
        node.put("passwordHash", hash);
        node.put("initialPasswordPending", pending);
        node.put("createdAt", (createdAt == null ? Instant.now() : createdAt).toString());
        writeSecure(serverFile(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node) + System.lineSeparator());
    }

    private static void writeSecure(Path target, String content) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        try {
            restrict(temp);
            Files.writeString(temp, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / 非 POSIX 文件系统：尽力而为。
        }
    }

    private static String generatePassword() {
        while (true) {
            StringBuilder builder = new StringBuilder(PASSWORD_LENGTH);
            for (int i = 0; i < PASSWORD_LENGTH; i++) {
                builder.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
            }
            String candidate = builder.toString();
            boolean upper = candidate.chars().anyMatch(Character::isUpperCase);
            boolean lower = candidate.chars().anyMatch(Character::isLowerCase);
            boolean digit = candidate.chars().anyMatch(Character::isDigit);
            boolean symbol = candidate.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
            if (upper && lower && digit && symbol) {
                return candidate;
            }
        }
    }
}
