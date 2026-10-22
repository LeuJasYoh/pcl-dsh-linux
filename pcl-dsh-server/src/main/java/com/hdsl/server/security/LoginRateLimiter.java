package com.hdsl.server.security;

import com.hdsl.server.config.PclProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 按来源 IP 统计登录失败：窗口内（默认 60 秒）连续失败达到上限（默认 5 次）后锁定 60 秒。
 * 纯内存实现，适合单机单用户场景；Nginx 层的 limit_req 仍作为第一道防线。
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_TRACKED_IPS = 4096;

    private static final class Entry {
        final Deque<Instant> failures = new ArrayDeque<>();
        Instant lockedUntil;
        Instant lastSeen = Instant.now();
    }

    private final PclProperties properties;
    private final Map<String, Entry> entries = new HashMap<>();

    public LoginRateLimiter(PclProperties properties) {
        this.properties = properties;
    }

    public synchronized boolean isLocked(String ip) {
        Entry entry = entries.get(ip);
        if (entry == null) {
            return false;
        }
        Instant now = Instant.now();
        entry.lastSeen = now;
        pruneFailures(entry, now);
        if (entry.lockedUntil == null) {
            return false;
        }
        if (now.isBefore(entry.lockedUntil)) {
            return true;
        }
        entry.lockedUntil = null;
        entry.failures.clear();
        return false;
    }

    /** 记录一次失败；返回 true 表示本次失败已触发锁定。 */
    public synchronized boolean recordFailure(String ip) {
        Instant now = Instant.now();
        if (entries.size() >= MAX_TRACKED_IPS) {
            purgeStale(now);
        }
        Entry entry = entries.computeIfAbsent(ip, key -> new Entry());
        entry.lastSeen = now;
        pruneFailures(entry, now);
        entry.failures.addLast(now);
        if (entry.failures.size() >= Math.max(1, properties.getLoginMaxFailures())) {
            entry.lockedUntil = now.plus(properties.getLoginLock());
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess(String ip) {
        entries.remove(ip);
    }

    private void pruneFailures(Entry entry, Instant now) {
        Instant threshold = now.minus(properties.getLoginWindow());
        while (!entry.failures.isEmpty() && entry.failures.peekFirst().isBefore(threshold)) {
            entry.failures.removeFirst();
        }
    }

    private void purgeStale(Instant now) {
        Instant threshold = now.minus(properties.getLoginWindow().multipliedBy(10));
        entries.entrySet().removeIf(e -> e.getValue().lockedUntil == null
                && e.getValue().failures.isEmpty()
                && e.getValue().lastSeen.isBefore(threshold));
    }
}
