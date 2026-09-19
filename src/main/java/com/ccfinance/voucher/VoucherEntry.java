package com.ccfinance.voucher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "voucher_entries")
public class VoucherEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private JournalVoucher voucher;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "account_code", nullable = false, length = 64)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private EntryDirection direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "summary", length = 256)
    private String summary;

    protected VoucherEntry() {
    }

    public VoucherEntry(String accountCode, EntryDirection direction, BigDecimal amount, String summary) {
        this.accountCode = accountCode;
        this.direction = direction;
        this.amount = amount;
        this.summary = summary;
    }

    void assignVoucher(JournalVoucher voucher, int lineNo) {
        this.voucher = voucher;
        this.lineNo = lineNo;
    }

    public Long getId() {
        return id;
    }

    public JournalVoucher getVoucher() {
        return voucher;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSummary() {
        return summary;
    }
}
