package com.ccfinance.period.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReopenRequest(
        @NotBlank(message = "反关账原因不能为空")
        @Size(max = 500, message = "反关账原因长度不能超过500")
        String reason) {
}
