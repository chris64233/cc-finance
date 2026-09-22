package com.ccfinance.carryforward.dto;

import jakarta.validation.constraints.NotBlank;

public record CarryForwardRequest(
        @NotBlank(message = "承接损益的所有者权益科目编码不能为空")
        String equityAccountCode) {
}
