package com.ccfinance.period.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PeriodRequest(
        @NotNull(message = "年份不能为空")
        @Min(value = 1900, message = "年份不能早于1900")
        @Max(value = 2100, message = "年份不能晚于2100")
        Integer year,
        @NotNull(message = "月份不能为空")
        @Min(value = 1, message = "月份必须在1到12之间")
        @Max(value = 12, message = "月份必须在1到12之间")
        Integer month) {
}
