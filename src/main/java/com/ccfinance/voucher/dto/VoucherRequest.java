package com.ccfinance.voucher.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VoucherRequest(
        @NotBlank(message = "业务唯一号不能为空") String bizKey,
        @NotNull(message = "凭证日期不能为空") LocalDate voucherDate,
        String summary,
        @NotNull(message = "分录明细不能为空")
        @Size(min = 2, message = "每张凭证至少包含两条分录")
        List<@Valid EntryRequest> entries) {
}
