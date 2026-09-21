package com.ccfinance.account;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.code = :code")
    Optional<Account> findByCodeForUpdate(@Param("code") String code);

    boolean existsByCode(String code);

    List<Account> findByCodeIn(Collection<String> codes);
}
