package com.ccfinance.voucher.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherStatus;

public record VoucherResponse(
        String voucherNo,
        String bizKey,
        LocalDate voucherDate,
        String summary,
        VoucherStatus status,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        Instant createdAt,
        List<EntryResponse> entries) {

    public static VoucherResponse from(JournalVoucher voucher) {
        return new VoucherResponse(
                voucher.getVoucherNo(),
                voucher.getBizKey(),
                voucher.getVoucherDate(),
                voucher.getSummary(),
                voucher.getStatus(),
                voucher.getDebitTotal(),
                voucher.getCreditTotal(),
                voucher.getCreatedAt(),
                voucher.getEntries().stream().map(EntryResponse::from).toList());
    }
}
