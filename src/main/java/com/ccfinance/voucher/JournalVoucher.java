package com.ccfinance.voucher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "journal_vouchers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_journal_vouchers_voucher_no", columnNames = "voucher_no"),
        @UniqueConstraint(name = "uk_journal_vouchers_biz_key", columnNames = "biz_key"),
        @UniqueConstraint(name = "uk_journal_vouchers_reversal_of", columnNames = "reversal_of_voucher_no"),
        @UniqueConstraint(name = "uk_journal_vouchers_carry_forward_period",
                columnNames = "carry_forward_period_code"),
        @UniqueConstraint(name = "uk_journal_vouchers_balance_carry_forward_period",
                columnNames = "balance_carry_forward_period_code")
})
public class JournalVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "journal_voucher_seq")
    @SequenceGenerator(name = "journal_voucher_seq", sequenceName = "journal_voucher_seq", allocationSize = 1)
    private Long id;

    @Column(name = "voucher_no", nullable = false, length = 32)
    private String voucherNo;

    @Column(name = "biz_key", nullable = false, length = 128)
    private String bizKey;

    @Column(nullable = false)
    private LocalDate voucherDate;

    @Column(length = 256)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VoucherStatus status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal debitTotal;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal creditTotal;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "reversal_of_voucher_no", length = 32)
    private String reversalOfVoucherNo;

    @Column(name = "carry_forward_period_code", length = 7, updatable = false)
    private String carryForwardPeriodCode;

    @Column(name = "carry_forward_equity_account_code", length = 64, updatable = false)
    private String carryForwardEquityAccountCode;

    @Column(name = "balance_carry_forward_period_code", length = 7, updatable = false)
    private String balanceCarryForwardPeriodCode;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<JournalEntry> entries = new ArrayList<>();

    protected JournalVoucher() {
    }

    public JournalVoucher(String bizKey, LocalDate voucherDate, String summary,
            BigDecimal debitTotal, BigDecimal creditTotal, String requestFingerprint) {
        this.voucherNo = "PENDING";
        this.bizKey = bizKey;
        this.voucherDate = voucherDate;
        this.summary = summary;
        this.status = VoucherStatus.POSTED;
        this.debitTotal = debitTotal;
        this.creditTotal = creditTotal;
        this.createdAt = Instant.now();
        this.requestFingerprint = requestFingerprint;
    }

    public void addEntry(JournalEntry entry) {
        entry.setVoucher(this);
        entries.add(entry);
    }

    public void assignVoucherNo() {
        this.voucherNo = "JV-" + String.format("%08d", id);
    }

    public void markAsReversalOf(String originalVoucherNo) {
        this.reversalOfVoucherNo = originalVoucherNo;
    }

    public void markAsProfitLossCarryForward(String periodCode, String equityAccountCode) {
        this.carryForwardPeriodCode = periodCode;
        this.carryForwardEquityAccountCode = equityAccountCode;
    }

    public void markAsBalanceCarryForward(String periodCode) {
        this.balanceCarryForwardPeriodCode = periodCode;
    }

    public Long getId() {
        return id;
    }

    public String getVoucherNo() {
        return voucherNo;
    }

    public String getBizKey() {
        return bizKey;
    }

    public LocalDate getVoucherDate() {
        return voucherDate;
    }

    public String getSummary() {
        return summary;
    }

    public VoucherStatus getStatus() {
        return status;
    }

    public BigDecimal getDebitTotal() {
        return debitTotal;
    }

    public BigDecimal getCreditTotal() {
        return creditTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getReversalOfVoucherNo() {
        return reversalOfVoucherNo;
    }

    public String getCarryForwardPeriodCode() {
        return carryForwardPeriodCode;
    }

    public String getCarryForwardEquityAccountCode() {
        return carryForwardEquityAccountCode;
    }

    public String getBalanceCarryForwardPeriodCode() {
        return balanceCarryForwardPeriodCode;
    }

    public List<JournalEntry> getEntries() {
        return entries;
    }
}
