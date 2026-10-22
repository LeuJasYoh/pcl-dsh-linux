package com.hdsl.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用轻量定时任务（目前用于 PreviewStore/ArtifactStore 的过期清理）。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
