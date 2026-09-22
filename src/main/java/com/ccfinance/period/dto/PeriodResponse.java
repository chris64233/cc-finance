package com.ccfinance.period.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.ccfinance.period.AccountingPeriod;
import com.ccfinance.period.PeriodStatus;

public record PeriodResponse(
        String periodCode,
        int year,
        int month,
        LocalDate startDate,
        LocalDate endDate,
        PeriodStatus status,
        Instant createdAt,
        Instant closedAt,
        Instant reopenedAt,
        String reopenReason) {

    public static PeriodResponse from(AccountingPeriod period) {
        return new PeriodResponse(
                period.getPeriodCode(),
                period.getPeriodYear(),
                period.getPeriodMonth(),
                period.getStartDate(),
                period.getEndDate(),
                period.getStatus(),
                period.getCreatedAt(),
                period.getClosedAt(),
                period.getReopenedAt(),
                period.getReopenReason());
    }
}
