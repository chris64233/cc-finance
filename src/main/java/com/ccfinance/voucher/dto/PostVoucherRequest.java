package com.ccfinance.voucher.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record PostVoucherRequest(
        @NotBlank(message = "业务唯一号不能为空")
        @Size(max = 128, message = "业务唯一号长度不能超过128")
        String bizId,

        @NotNull(message = "凭证日期不能为空")
        LocalDate voucherDate,

        @Size(max = 256, message = "摘要长度不能超过256")
        String summary,

        @NotNull(message = "分录明细不能为空")
        @Size(min = 2, message = "每张凭证至少包含两条分录")
        List<@Valid VoucherEntryRequest> entries) {
}
