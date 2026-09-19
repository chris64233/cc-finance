package com.ccfinance.voucher.dto;

import com.ccfinance.voucher.EntryDirection;
import com.ccfinance.voucher.VoucherEntry;

import java.math.BigDecimal;

public record VoucherEntryResponse(
        int lineNo,
        String accountCode,
        EntryDirection direction,
        BigDecimal amount,
        String summary) {

    public static VoucherEntryResponse from(VoucherEntry entry) {
        return new VoucherEntryResponse(entry.getLineNo(), entry.getAccountCode(),
                entry.getDirection(), entry.getAmount(), entry.getSummary());
    }
}
