package com.ccfinance.voucher.dto;

import java.math.BigDecimal;

import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.JournalEntry;

public record EntryResponse(
        String accountCode,
        Direction direction,
        BigDecimal amount,
        String summary) {

    public static EntryResponse from(JournalEntry entry) {
        return new EntryResponse(
                entry.getAccountCode(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getSummary());
    }
}
