package com.ccfinance.voucher;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherCorrectionRepository extends JpaRepository<VoucherCorrection, Long> {

    Optional<VoucherCorrection> findByBizKey(String bizKey);

    boolean existsByOriginalVoucherNo(String originalVoucherNo);

    @Query("select c from VoucherCorrection c where c.originalVoucherNo = :voucherNo "
            + "or c.reversalVoucherNo = :voucherNo or c.replacementVoucherNo = :voucherNo")
    Optional<VoucherCorrection> findByAnyVoucherNo(@Param("voucherNo") String voucherNo);
}
