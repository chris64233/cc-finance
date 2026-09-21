package com.ccfinance.voucher;

import java.math.BigDecimal;

public record AccountPeriodBalance(String accountCode, BigDecimal debitTotal, BigDecimal creditTotal) {
}
