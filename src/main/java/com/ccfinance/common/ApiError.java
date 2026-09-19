package com.ccfinance.common;

import java.time.Instant;

/**
 * 统一错误响应体，不向客户端暴露堆栈信息。
 */
public record ApiError(
        int status,
        String code,
        String message,
        String path,
        Instant timestamp) {

    public static ApiError of(int status, ErrorCode code, String message, String path) {
        return new ApiError(status, code.name(), message, path, Instant.now());
    }
}
