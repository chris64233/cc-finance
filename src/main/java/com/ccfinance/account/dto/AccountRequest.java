package com.ccfinance.account.dto;

import com.ccfinance.account.AccountCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AccountRequest(
        @NotBlank(message = "科目编码不能为空") String code,
        @NotBlank(message = "科目名称不能为空") String name,
        @NotNull(message = "科目类别不能为空") AccountCategory category,
        Boolean enabled) {
}
