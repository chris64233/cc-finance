package com.ccfinance.voucher;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_vouchers")
public class JournalVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_no", unique = true, length = 32)
    private String voucherNo;

    @Column(name = "biz_id", nullable = false, unique = true, length = 128)
    private String bizId;

    @Column(name = "voucher_date", nullable = false)
    private LocalDate voucherDate;

    @Column(name = "summary", length = 256)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VoucherStatus status = VoucherStatus.POSTED;

    @Column(name = "debit_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal debitTotal;

    @Column(name = "credit_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal creditTotal;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<VoucherEntry> entries = new ArrayList<>();

    protected JournalVoucher() {
    }

    public JournalVoucher(String bizId, LocalDate voucherDate, String summary, String requestHash) {
        this.bizId = bizId;
        this.voucherDate = voucherDate;
        this.summary = summary;
        this.requestHash = requestHash;
    }

    public void addEntry(VoucherEntry entry) {
        entry.assignVoucher(this, entries.size() + 1);
        entries.add(entry);
    }

    public void assignVoucherNo(String voucherNo) {
        if (this.voucherNo != null) {
            throw new IllegalStateException("凭证号一旦生成不可修改");
        }
        this.voucherNo = voucherNo;
    }

    public void computeTotals() {
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (VoucherEntry entry : entries) {
            if (entry.getDirection() == EntryDirection.DEBIT) {
                debit = debit.add(entry.getAmount());
            } else {
                credit = credit.add(entry.getAmount());
            }
        }
        this.debitTotal = debit;
        this.creditTotal = credit;
    }

    public Long getId() {
        return id;
    }

    public String getVoucherNo() {
        return voucherNo;
    }

    public String getBizId() {
        return bizId;
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

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<VoucherEntry> getEntries() {
        return entries;
    }
}
