package com.ccfinance.voucher;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VoucherRepository extends JpaRepository<JournalVoucher, Long> {

    Optional<JournalVoucher> findByBizKey(String bizKey);

    Optional<JournalVoucher> findByVoucherNo(String voucherNo);

    Optional<JournalVoucher> findByReversedVoucherNo(String reversedVoucherNo);
}
