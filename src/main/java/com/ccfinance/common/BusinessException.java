package com.ccfinance.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态与稳定的业务错误码。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public BusinessException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public ErrorCode getCode() {
        return code;
    }
}
