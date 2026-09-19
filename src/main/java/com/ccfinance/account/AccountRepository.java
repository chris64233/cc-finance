package com.ccfinance.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByCode(String code);

    boolean existsByCode(String code);

    List<Account> findByCodeIn(Collection<String> codes);
}
