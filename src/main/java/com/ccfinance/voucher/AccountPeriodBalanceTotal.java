package com.ccfinance.voucher;

import java.math.BigDecimal;

public record AccountPeriodBalanceTotal(String accountCode, BigDecimal debitTotal, BigDecimal creditTotal) {
}
