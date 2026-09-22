package com.ccfinance.period.dto;

import jakarta.validation.constraints.NotBlank;

public record PeriodReopenRequest(
        @NotBlank(message = "反关账原因不能为空") String reason) {
}
