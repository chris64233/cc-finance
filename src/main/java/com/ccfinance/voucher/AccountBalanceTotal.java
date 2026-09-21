package com.ccfinance.voucher;

import java.math.BigDecimal;

public record AccountBalanceTotal(BigDecimal debitTotal, BigDecimal creditTotal) {
}
