package com.ccfinance.account.dto;

import com.ccfinance.account.AccountCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank(message = "科目编码不能为空")
        @Size(max = 64, message = "科目编码长度不能超过64")
        String code,

        @NotBlank(message = "科目名称不能为空")
        @Size(max = 128, message = "科目名称长度不能超过128")
        String name,

        @NotNull(message = "科目类别不能为空")
        AccountCategory category,

        Boolean enabled) {
}
