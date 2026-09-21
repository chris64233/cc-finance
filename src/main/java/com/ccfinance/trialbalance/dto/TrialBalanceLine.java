package com.ccfinance.trialbalance.dto;

import java.math.BigDecimal;

import com.ccfinance.account.AccountCategory;
import com.ccfinance.trialbalance.BalanceDirection;

public record TrialBalanceLine(
        String accountCode,
        String accountName,
        AccountCategory category,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        BalanceDirection balanceDirection,
        BigDecimal balanceAmount) {
}
