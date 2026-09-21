package com.ccfinance.trialbalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.common.ApiException;
import com.ccfinance.period.AccountingPeriod;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.trialbalance.dto.TrialBalanceLine;
import com.ccfinance.trialbalance.dto.TrialBalanceResponse;
import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.JournalEntry;
import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherRepository;
import com.ccfinance.voucher.VoucherStatus;

@Service
public class TrialBalanceService {

    private final PeriodRepository periodRepository;
    private final VoucherRepository voucherRepository;
    private final AccountRepository accountRepository;

    public TrialBalanceService(PeriodRepository periodRepository, VoucherRepository voucherRepository,
            AccountRepository accountRepository) {
        this.periodRepository = periodRepository;
        this.voucherRepository = voucherRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public TrialBalanceResponse getByPeriod(String periodCode) {
        AccountingPeriod period = periodRepository.findByPeriodCode(periodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + periodCode));

        List<JournalVoucher> vouchers = voucherRepository.findByStatusAndVoucherDateBetween(
                VoucherStatus.POSTED, period.getStartDate(), period.getEndDate());

        Map<String, BigDecimal[]> amountsByAccount = new HashMap<>();
        for (JournalVoucher voucher : vouchers) {
            for (JournalEntry entry : voucher.getEntries()) {
                BigDecimal[] amounts = amountsByAccount.computeIfAbsent(entry.getAccountCode(),
                        code -> new BigDecimal[] { zero(), zero() });
                if (entry.getDirection() == Direction.DEBIT) {
                    amounts[0] = amounts[0].add(entry.getAmount());
                } else {
                    amounts[1] = amounts[1].add(entry.getAmount());
                }
            }
        }

        Map<String, Account> accountsByCode = accountRepository.findAll().stream()
                .collect(Collectors.toMap(Account::getCode, Function.identity()));

        BigDecimal debitTotal = zero();
        BigDecimal creditTotal = zero();
        List<TrialBalanceLine> lines = amountsByAccount.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toLine(entry.getKey(), entry.getValue(), accountsByCode))
                .toList();
        for (TrialBalanceLine line : lines) {
            debitTotal = debitTotal.add(line.debitAmount());
            creditTotal = creditTotal.add(line.creditAmount());
        }

        return new TrialBalanceResponse(period.getPeriodCode(), debitTotal, creditTotal, lines);
    }

    private TrialBalanceLine toLine(String accountCode, BigDecimal[] amounts,
            Map<String, Account> accountsByCode) {
        Account account = accountsByCode.get(accountCode);
        if (account == null) {
            throw new IllegalStateException("分录引用的科目不存在: " + accountCode);
        }
        BigDecimal debit = amounts[0].setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal credit = amounts[1].setScale(2, RoundingMode.UNNECESSARY);
        BalanceDirection direction;
        if (debit.compareTo(credit) > 0) {
            direction = BalanceDirection.DEBIT;
        } else if (credit.compareTo(debit) > 0) {
            direction = BalanceDirection.CREDIT;
        } else {
            direction = BalanceDirection.NONE;
        }
        BigDecimal balance = debit.subtract(credit).abs().setScale(2, RoundingMode.UNNECESSARY);
        return new TrialBalanceLine(account.getCode(), account.getName(), account.getCategory(),
                debit, credit, direction, balance);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }
}
