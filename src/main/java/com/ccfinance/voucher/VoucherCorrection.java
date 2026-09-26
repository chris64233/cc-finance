package com.ccfinance.voucher;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "voucher_corrections", uniqueConstraints = {
        @UniqueConstraint(name = "uk_voucher_corrections_biz_key", columnNames = "biz_key"),
        @UniqueConstraint(name = "uk_voucher_corrections_original", columnNames = "original_voucher_no"),
        @UniqueConstraint(name = "uk_voucher_corrections_reversal", columnNames = "reversal_voucher_no"),
        @UniqueConstraint(name = "uk_voucher_corrections_replacement", columnNames = "replacement_voucher_no")
})
public class VoucherCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "biz_key", nullable = false, length = 128)
    private String bizKey;

    @Column(name = "original_voucher_no", nullable = false, length = 32)
    private String originalVoucherNo;

    @Column(name = "reversal_voucher_no", nullable = false, length = 32)
    private String reversalVoucherNo;

    @Column(name = "replacement_voucher_no", nullable = false, length = 32)
    private String replacementVoucherNo;

    @Column(nullable = false)
    private LocalDate correctionDate;

    @Column(nullable = false, length = 64)
    private String requestFingerprint;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected VoucherCorrection() {
    }

    public VoucherCorrection(String bizKey, String originalVoucherNo, String reversalVoucherNo,
            String replacementVoucherNo, LocalDate correctionDate, String requestFingerprint) {
        this.bizKey = bizKey;
        this.originalVoucherNo = originalVoucherNo;
        this.reversalVoucherNo = reversalVoucherNo;
        this.replacementVoucherNo = replacementVoucherNo;
        this.correctionDate = correctionDate;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getBizKey() {
        return bizKey;
    }

    public String getOriginalVoucherNo() {
        return originalVoucherNo;
    }

    public String getReversalVoucherNo() {
        return reversalVoucherNo;
    }

    public String getReplacementVoucherNo() {
        return replacementVoucherNo;
    }

    public LocalDate getCorrectionDate() {
        return correctionDate;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
