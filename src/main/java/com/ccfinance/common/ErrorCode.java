package com.ccfinance.common;

/**
 * 稳定的业务错误码，随 API 返回给客户端。
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    ACCOUNT_CODE_CONFLICT,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_DISABLED,
    VOUCHER_UNBALANCED,
    VOUCHER_NOT_FOUND,
    BIZ_ID_CONFLICT,
    INTERNAL_ERROR
}
