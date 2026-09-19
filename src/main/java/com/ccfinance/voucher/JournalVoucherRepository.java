package com.ccfinance.voucher;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JournalVoucherRepository extends JpaRepository<JournalVoucher, Long> {

    @EntityGraph(attributePaths = "entries")
    Optional<JournalVoucher> findByVoucherNo(String voucherNo);

    @EntityGraph(attributePaths = "entries")
    Optional<JournalVoucher> findByBizId(String bizId);
}
