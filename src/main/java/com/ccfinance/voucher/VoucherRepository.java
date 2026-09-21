package com.ccfinance.voucher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<JournalVoucher, Long> {

    Optional<JournalVoucher> findByBizKey(String bizKey);

    Optional<JournalVoucher> findByVoucherNo(String voucherNo);

    Optional<JournalVoucher> findByReversalOfVoucherNo(String reversalOfVoucherNo);

    boolean existsByReversalOfVoucherNo(String reversalOfVoucherNo);

    @Query("select distinct v from JournalVoucher v left join fetch v.entries "
            + "where v.status = com.ccfinance.voucher.VoucherStatus.POSTED "
            + "and v.voucherDate between :startDate and :endDate")
    List<JournalVoucher> findPostedByVoucherDateBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("select new com.ccfinance.voucher.AccountBalanceTotal("
            + "sum(case when e.direction = com.ccfinance.voucher.Direction.DEBIT then e.amount end), "
            + "sum(case when e.direction = com.ccfinance.voucher.Direction.CREDIT then e.amount end)) "
            + "from JournalEntry e join e.voucher v "
            + "where e.accountCode = :accountCode "
            + "and v.status = com.ccfinance.voucher.VoucherStatus.POSTED")
    AccountBalanceTotal sumPostedDebitAndCreditByAccountCode(@Param("accountCode") String accountCode);
}
