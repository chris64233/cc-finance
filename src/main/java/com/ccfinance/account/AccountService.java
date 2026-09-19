package com.ccfinance.account;

import com.ccfinance.account.dto.AccountResponse;
import com.ccfinance.account.dto.CreateAccountRequest;
import com.ccfinance.common.BusinessException;
import com.ccfinance.common.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        String code = request.code().trim();
        String name = request.name().trim();
        if (code.isEmpty() || name.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "科目编码和名称去除首尾空白后不能为空");
        }
        boolean enabled = request.enabled() == null || request.enabled();
        Account account = new Account(code, name, request.category(), enabled);
        try {
            Account saved = accountRepository.saveAndFlush(account);
            return AccountResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.ACCOUNT_CODE_CONFLICT,
                    "科目编码已存在: " + code);
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getByCode(String code) {
        Account account = accountRepository.findByCode(code.trim())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND,
                        "科目不存在: " + code.trim()));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accountRepository.findAll().stream()
                .map(AccountResponse::from)
                .toList();
    }
}
