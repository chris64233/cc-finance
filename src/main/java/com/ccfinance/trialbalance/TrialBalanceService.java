package com.ccfinance.trialbalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.period.PeriodService;
import com.ccfinance.period.dto.PeriodResponse;
import com.ccfinance.trialbalance.dto.TrialBalanceItemResponse;
import com.ccfinance.trialbalance.dto.TrialBalanceResponse;
import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.JournalEntry;
import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherRepository;

@Service
public class TrialBalanceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final PeriodService periodService;
    private final VoucherRepository voucherRepository;
    private final AccountRepository accountRepository;

    public TrialBalanceService(PeriodService periodService, VoucherRepository voucherRepository,
            AccountRepository accountRepository) {
        this.periodService = periodService;
        this.voucherRepository = voucherRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public TrialBalanceResponse getByPeriodCode(String periodCode) {
        PeriodResponse period = periodService.getByCode(periodCode);

        Map<String, BigDecimal> debitByAccount = new HashMap<>();
        Map<String, BigDecimal> creditByAccount = new HashMap<>();
        List<JournalVoucher> vouchers = voucherRepository.findPostedByVoucherDateBetween(
                period.startDate(), period.endDate());
        for (JournalVoucher voucher : vouchers) {
            for (JournalEntry entry : voucher.getEntries()) {
                Map<String, BigDecimal> target = entry.getDirection() == Direction.DEBIT
                        ? debitByAccount
                        : creditByAccount;
                target.merge(entry.getAccountCode(), entry.getAmount(), BigDecimal::add);
            }
        }

        Map<String, Account> accounts = new HashMap<>();
        if (!debitByAccount.isEmpty() || !creditByAccount.isEmpty()) {
            Set<String> accountCodes = new TreeSet<>();
            accountCodes.addAll(debitByAccount.keySet());
            accountCodes.addAll(creditByAccount.keySet());
            for (Account account : accountRepository.findByCodeIn(accountCodes)) {
                accounts.put(account.getCode(), account);
            }
        }

        BigDecimal debitTotal = ZERO;
        BigDecimal creditTotal = ZERO;
        List<TrialBalanceItemResponse> items = new ArrayList<>();
        for (String accountCode : new TreeSet<>(accounts.keySet())) {
            Account account = accounts.get(accountCode);
            BigDecimal debitAmount = scale(debitByAccount.getOrDefault(accountCode, ZERO));
            BigDecimal creditAmount = scale(creditByAccount.getOrDefault(accountCode, ZERO));
            int comparison = debitAmount.compareTo(creditAmount);
            BalanceDirection balanceDirection = comparison > 0
                    ? BalanceDirection.DEBIT
                    : comparison < 0 ? BalanceDirection.CREDIT : BalanceDirection.NONE;
            BigDecimal balanceAmount = debitAmount.subtract(creditAmount).abs();
            debitTotal = debitTotal.add(debitAmount);
            creditTotal = creditTotal.add(creditAmount);
            items.add(new TrialBalanceItemResponse(
                    account.getCode(),
                    account.getName(),
                    account.getCategory(),
                    debitAmount,
                    creditAmount,
                    balanceDirection,
                    balanceAmount));
        }

        return new TrialBalanceResponse(period.periodCode(), scale(debitTotal), scale(creditTotal), items);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
