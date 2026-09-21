package com.ccfinance.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.dto.AccountRequest;
import com.ccfinance.account.dto.AccountResponse;
import com.ccfinance.common.ApiException;
import com.ccfinance.voucher.AccountBalanceTotal;
import com.ccfinance.voucher.VoucherRepository;

@Service
public class AccountService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final AccountRepository accountRepository;
    private final VoucherRepository voucherRepository;

    public AccountService(AccountRepository accountRepository, VoucherRepository voucherRepository) {
        this.accountRepository = accountRepository;
        this.voucherRepository = voucherRepository;
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

    @Transactional
    public AccountResponse deactivate(String code) {
        String normalizedCode = code.trim();
        Account account = accountRepository.findByCodeForUpdate(normalizedCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                        "科目不存在: " + normalizedCode));
        if (!account.isEnabled()) {
            return AccountResponse.from(account);
        }
        AccountBalanceTotal totals = voucherRepository.sumPostedDebitAndCreditByAccountCode(normalizedCode);
        BigDecimal debitTotal = totals.debitTotal() == null ? ZERO : totals.debitTotal();
        BigDecimal creditTotal = totals.creditTotal() == null ? ZERO : totals.creditTotal();
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_BALANCE_NOT_ZERO",
                    "科目累计借贷余额不为零，不能停用: " + normalizedCode
                            + " (借方累计=" + debitTotal + ", 贷方累计=" + creditTotal + ")");
        }
        account.disable();
        return AccountResponse.from(accountRepository.saveAndFlush(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accountRepository.findAll().stream()
                .map(AccountResponse::from)
                .toList();
    }
}
