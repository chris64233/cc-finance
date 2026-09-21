package com.ccfinance.trialbalance.dto;

import java.math.BigDecimal;
import java.util.List;

public record TrialBalanceResponse(
        String periodCode,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        List<TrialBalanceItemResponse> items) {
}
