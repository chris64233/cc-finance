package com.ccfinance.period;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PeriodRepository extends JpaRepository<AccountingPeriod, Long> {

    Optional<AccountingPeriod> findByPeriodCode(String periodCode);

    boolean existsByPeriodCode(String periodCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AccountingPeriod p where p.periodCode = :periodCode")
    Optional<AccountingPeriod> findByPeriodCodeForUpdate(@Param("periodCode") String periodCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AccountingPeriod p where p.periodCode > :periodCode"
            + " and p.status = com.ccfinance.period.PeriodStatus.CLOSED"
            + " order by p.periodCode asc")
    List<AccountingPeriod> findLaterClosedPeriodsForUpdate(@Param("periodCode") String periodCode);
}
