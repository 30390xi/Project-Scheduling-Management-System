package com.company.scheduler.shared.api;

import java.util.Map;
import org.slf4j.MDC;

public record ApiError(String code, String message, String requestId, Map<String, Object> details) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, MDC.get("requestId"), Map.of());
    }
}
