package com.ccfinance.voucher.dto;

import java.math.BigDecimal;

import com.ccfinance.voucher.Direction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record EntryRequest(
        @NotBlank(message = "科目编码不能为空") String accountCode,
        @NotNull(message = "借贷方向不能为空") Direction direction,
        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "金额必须大于0")
        @Digits(integer = 17, fraction = 2, message = "金额最多保留两位小数")
        BigDecimal amount,
        String summary) {
}
