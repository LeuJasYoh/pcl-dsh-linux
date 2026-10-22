package com.hdsl.server.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时能力探测摘要缓存（进程内存）：由安装/探测任务写入，GET /api/runtimes 的
 * compatibility 字段读取。服务重启后回到“待检测”，由用户重新探测，不写入磁盘。
 */
@Component
public class RuntimeCompatibility {

    private final Map<String, String> summaries = new ConcurrentHashMap<>();

    public void put(String version, String summary) {
        if (version == null || version.isBlank()) {
            return;
        }
        String value = summary == null || summary.isBlank() ? "待检测" : summary;
        summaries.put(version, value);
    }

    public String get(String version) {
        String value = version == null ? null : summaries.get(version);
        return value == null || value.isBlank() ? "待检测" : value;
    }

    public void remove(String version) {
        if (version != null) {
            summaries.remove(version);
        }
    }
}
