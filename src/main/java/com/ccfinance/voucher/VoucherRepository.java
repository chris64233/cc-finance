package com.ccfinance.voucher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherRepository extends JpaRepository<JournalVoucher, Long> {

    List<JournalVoucher> findByStatusAndVoucherDateBetween(VoucherStatus status, LocalDate startDate,
            LocalDate endDate);

    Optional<JournalVoucher> findByBizKey(String bizKey);

    Optional<JournalVoucher> findByVoucherNo(String voucherNo);

    Optional<JournalVoucher> findByReversalOfVoucherNo(String reversalOfVoucherNo);

    boolean existsByReversalOfVoucherNo(String reversalOfVoucherNo);
}
