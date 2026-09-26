package com.ccfinance.voucher.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CorrectionRequest(
        @NotBlank(message = "业务唯一号不能为空")
        @Size(max = 100, message = "业务唯一号最长100个字符") String bizKey,
        @NotNull(message = "更正日期不能为空") LocalDate voucherDate,
        String reversalSummary,
        String replacementSummary,
        @NotNull(message = "替换凭证分录明细不能为空")
        @Size(min = 2, message = "每张凭证至少包含两条分录")
        List<@Valid EntryRequest> entries) {
}
