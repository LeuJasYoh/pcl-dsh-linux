package com.hdsl.server.api;

import com.hdsl.server.log.LogFiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志尾部读取（方案 §4.2）：读取 data/logs 下最近的应用/任务日志。
 * 返回的行已脱敏（不含密钥/token）；无日志文件时返回空列表。
 */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private final LogFiles logs;

    public LogsController(LogFiles logs) {
        this.logs = logs;
    }

    /** lines 默认 800，有效范围 1–2000（越界按边界处理）。 */
    @GetMapping("/tail")
    public ApiDtos.LogTailDto tail(@RequestParam(value = "lines", defaultValue = "800") int lines) {
        return new ApiDtos.LogTailDto(logs.tail(lines));
    }
}
