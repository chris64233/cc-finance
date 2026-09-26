package com.ccfinance.voucher;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherCorrectionRepository extends JpaRepository<VoucherCorrection, Long> {

    Optional<VoucherCorrection> findByBizKey(String bizKey);

    Optional<VoucherCorrection> findByOriginalVoucherNo(String originalVoucherNo);

    boolean existsByOriginalVoucherNo(String originalVoucherNo);

    boolean existsByReplacementVoucherNo(String replacementVoucherNo);
}
