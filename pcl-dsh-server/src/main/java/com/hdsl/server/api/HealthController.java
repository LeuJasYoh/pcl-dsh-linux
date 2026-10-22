package com.hdsl.server.api;

import com.hdsl.server.config.PclProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 匿名健康检查端点，供 systemd / Nginx / 监控使用；不含任何敏感信息。 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final Instant startedAt = Instant.now();
    private final PclProperties properties;

    public HealthController(PclProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public ApiDtos.HealthDto health() {
        return new ApiDtos.HealthDto("UP", properties.getVersion(),
                Instant.now().getEpochSecond() - startedAt.getEpochSecond());
    }
}
