package com.ccfinance.period;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "accounting_periods", uniqueConstraints = {
        @UniqueConstraint(name = "uk_accounting_periods_code", columnNames = "period_code"),
        @UniqueConstraint(name = "uk_accounting_periods_year_month", columnNames = { "period_year", "period_month" })
})
public class AccountingPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_code", nullable = false, length = 7)
    private String periodCode;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PeriodStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;

    protected AccountingPeriod() {
    }

    public AccountingPeriod(String periodCode, int periodYear, int periodMonth,
            LocalDate startDate, LocalDate endDate) {
        this.periodCode = periodCode;
        this.periodYear = periodYear;
        this.periodMonth = periodMonth;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = PeriodStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public void close() {
        this.status = PeriodStatus.CLOSED;
        this.closedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public int getPeriodYear() {
        return periodYear;
    }

    public int getPeriodMonth() {
        return periodMonth;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public PeriodStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
