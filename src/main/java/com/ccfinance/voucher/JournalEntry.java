package com.ccfinance.voucher;

import java.math.BigDecimal;

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

@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private JournalVoucher voucher;

    @Column(nullable = false)
    private int lineNo;

    @Column(nullable = false, length = 64)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 256)
    private String summary;

    protected JournalEntry() {
    }

    public JournalEntry(int lineNo, String accountCode, Direction direction, BigDecimal amount, String summary) {
        this.lineNo = lineNo;
        this.accountCode = accountCode;
        this.direction = direction;
        this.amount = amount;
        this.summary = summary;
    }

    void setVoucher(JournalVoucher voucher) {
        this.voucher = voucher;
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public Direction getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSummary() {
        return summary;
    }
}
