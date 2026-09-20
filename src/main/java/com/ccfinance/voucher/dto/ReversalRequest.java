package com.ccfinance.voucher.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReversalRequest(
        @NotBlank(message = "业务唯一号不能为空") String bizKey,
        @NotNull(message = "冲销日期不能为空") LocalDate reversalDate,
        String summary) {
}
