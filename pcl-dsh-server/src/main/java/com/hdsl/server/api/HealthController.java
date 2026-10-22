package com.hdsl.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Anonymous health endpoint used by systemd checks and reverse proxies. */
@RestController
@RequestMapping("/api")
public class HealthController {
    private final Instant startedAt = Instant.now();

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("version", "0.3.0");
        body.put("uptimeSeconds", Instant.now().getEpochSecond() - startedAt.getEpochSecond());
        return body;
    }
}
