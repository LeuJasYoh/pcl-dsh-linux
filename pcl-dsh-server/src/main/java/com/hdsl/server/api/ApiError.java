package com.hdsl.server.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** 统一错误响应：{"code","message","details","traceId"}。 */
public record ApiError(String code, String message, Map<String, Object> details, String traceId) {

    public ApiError {
        code = code == null ? "INTERNAL" : code;
        message = message == null ? "服务器内部错误。" : message;
        Map<String, Object> safeDetails = new LinkedHashMap<>();
        if (details != null) {
            details.forEach((key, value) -> {
                if (key != null && value != null) {
                    safeDetails.put(key, value);
                }
            });
        }
        details = Map.copyOf(safeDetails);
        traceId = traceId == null ? "" : traceId;
    }
}
