package com.hdsl.account;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM protector for headless Linux servers.
 * Master key comes from the PCL_DSH_SECRET_KEY environment variable or from config/secret.key (0600).
 * The file is generated on first use and never logged.
 */
final class LinuxSecretProtector implements SecretProtector {
    static final String ENV_KEY = "PCL_DSH_SECRET_KEY";
    private static final byte VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path keyFile;
    private volatile SecretKeySpec key;

    LinuxSecretProtector(Path keyFile) {
        this.keyFile = keyFile.toAbsolutePath().normalize();
    }

    static LinuxSecretProtector forRoot(Path dataRoot) {
        return new LinuxSecretProtector(dataRoot.resolve("config").resolve("secret.key"));
    }

    @Override
    public byte[] protect(byte[] plaintext) throws IOException {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] out = new byte[1 + IV_BYTES + encrypted.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(encrypted, 0, out, 1 + IV_BYTES, encrypted.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("无法加密账户密钥。", e);
        }
    }

    @Override
    public byte[] unprotect(byte[] ciphertext) throws IOException {
        if (ciphertext == null || ciphertext.length < 1 + IV_BYTES + 16 || ciphertext[0] != VERSION) {
            throw new IOException("账户加密数据格式不受支持，请重新保存 API Key。");
        }
        try {
            byte[] iv = Arrays.copyOfRange(ciphertext, 1, 1 + IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.length - 1 - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IOException("无法解密账户密钥，请确认主密钥未更换后重新保存 API Key。", e);
        }
    }

    private SecretKeySpec key() throws IOException {
        SecretKeySpec loaded = key;
        if (loaded != null) return loaded;
        synchronized (this) {
            if (key != null) return key;
            key = new SecretKeySpec(loadOrCreateKey(), "AES");
            return key;
        }
    }

    private byte[] loadOrCreateKey() throws IOException {
        String configured = System.getenv(ENV_KEY);
        if (configured != null && !configured.isBlank()) {
            byte[] parsed = decodeKey(configured, ENV_KEY);
            if (parsed.length != KEY_BYTES) throw new IOException(ENV_KEY + " 必须为 32 字节密钥（Base64 或 64 位 Hex）。");
            return parsed;
        }
        if (Files.isRegularFile(keyFile)) {
            String text = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
            byte[] parsed = decodeKey(text, keyFile.toString());
            if (parsed.length != KEY_BYTES) throw new IOException("主密钥文件格式无效：" + keyFile);
            return parsed;
        }
        byte[] generated = new byte[KEY_BYTES];
        RANDOM.nextBytes(generated);
        writeKeyFile(generated);
        return generated;
    }

    private static byte[] decodeKey(String value, String source) throws IOException {
        String text = value.strip();
        try {
            if (text.matches("[0-9a-fA-F]{64}")) return HexFormat.of().parseHex(text);
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("主密钥格式无效（" + source + "）。", e);
        }
    }

    private void writeKeyFile(byte[] raw) throws IOException {
        Path parent = keyFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "secret-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temp, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows or non-POSIX file system: permissions are best effort.
            }
            try {
                Files.move(temp, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // best effort
            }
        } finally {
            if (!moved) Files.deleteIfExists(temp);
        }
    }
}
