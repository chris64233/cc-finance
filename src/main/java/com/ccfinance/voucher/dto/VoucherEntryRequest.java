package com.ccfinance.voucher.dto;

import com.ccfinance.voucher.EntryDirection;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record VoucherEntryRequest(
        @NotBlank(message = "科目编码不能为空")
        @Size(max = 64, message = "科目编码长度不能超过64")
        String accountCode,

        @NotNull(message = "借贷方向不能为空")
        EntryDirection direction,

        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "金额必须大于0")
        @Digits(integer = 17, fraction = 2, message = "金额最多保留两位小数")
        BigDecimal amount,

        @Size(max = 256, message = "摘要长度不能超过256")
        String summary) {
}
