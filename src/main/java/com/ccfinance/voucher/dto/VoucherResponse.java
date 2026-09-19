package com.ccfinance.voucher.dto;

import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record VoucherResponse(
        String voucherNo,
        String bizId,
        LocalDate voucherDate,
        String summary,
        VoucherStatus status,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        Instant createdAt,
        List<VoucherEntryResponse> entries) {

    public static VoucherResponse from(JournalVoucher voucher) {
        List<VoucherEntryResponse> entries = voucher.getEntries().stream()
                .map(VoucherEntryResponse::from)
                .toList();
        return new VoucherResponse(voucher.getVoucherNo(), voucher.getBizId(),
                voucher.getVoucherDate(), voucher.getSummary(), voucher.getStatus(),
                voucher.getDebitTotal(), voucher.getCreditTotal(), voucher.getCreatedAt(),
                entries);
    }
}
