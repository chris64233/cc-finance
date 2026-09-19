package com.ccfinance.account;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.dto.AccountRequest;
import com.ccfinance.account.dto.AccountResponse;
import com.ccfinance.common.ApiException;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse create(AccountRequest request) {
        String code = request.code().trim();
        String name = request.name().trim();
        if (accountRepository.existsByCode(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS",
                    "科目编码已存在: " + code);
        }
        boolean enabled = request.enabled() == null || request.enabled();
        Account account = new Account(code, name, request.category(), enabled);
        try {
            return AccountResponse.from(accountRepository.saveAndFlush(account));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS",
                    "科目编码已存在: " + code);
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getByCode(String code) {
        return accountRepository.findByCode(code.trim())
                .map(AccountResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                        "科目不存在: " + code));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accountRepository.findAll().stream()
                .map(AccountResponse::from)
                .toList();
    }
}
