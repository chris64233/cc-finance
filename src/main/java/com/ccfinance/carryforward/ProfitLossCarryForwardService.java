package com.ccfinance.carryforward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountCategory;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.carryforward.dto.CarryForwardRequest;
import com.ccfinance.common.ApiException;
import com.ccfinance.period.AccountingPeriod;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.period.PeriodStatus;
import com.ccfinance.voucher.AccountPeriodBalanceTotal;
import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.JournalEntry;
import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherRepository;
import com.ccfinance.voucher.dto.VoucherResponse;

@Service
public class ProfitLossCarryForwardService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final PeriodRepository periodRepository;
    private final VoucherRepository voucherRepository;
    private final AccountRepository accountRepository;

    public ProfitLossCarryForwardService(PeriodRepository periodRepository,
            VoucherRepository voucherRepository, AccountRepository accountRepository) {
        this.periodRepository = periodRepository;
        this.voucherRepository = voucherRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public VoucherResponse carryForward(String periodCode, CarryForwardRequest request) {
        String normalizedPeriodCode = periodCode.trim();
        String equityAccountCode = request.equityAccountCode().trim();

        AccountingPeriod period = periodRepository.findByPeriodCodeForUpdate(normalizedPeriodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + normalizedPeriodCode));
        if (period.getStatus() == PeriodStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_CLOSED",
                    "会计期间已关账，不能损益结转: " + normalizedPeriodCode);
        }

        var existing = voucherRepository.findByCarryForwardPeriodCode(normalizedPeriodCode);
        if (existing.isPresent()) {
            JournalVoucher voucher = existing.get();
            if (voucher.getCarryForwardEquityAccountCode().equals(equityAccountCode)) {
                return VoucherResponse.from(voucher);
            }
            throw new ApiException(HttpStatus.CONFLICT, "PROFIT_LOSS_CARRY_FORWARD_CONFLICT",
                    "该期间已使用其他承接科目完成损益结转: " + normalizedPeriodCode
                            + " 已使用科目: " + voucher.getCarryForwardEquityAccountCode());
        }

        Account equityAccount = accountRepository.findByCodeForUpdate(equityAccountCode)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ACCOUNT_NOT_FOUND", "承接损益的科目不存在: " + equityAccountCode));
        if (!equityAccount.isEnabled()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ACCOUNT_DISABLED", "承接损益的科目已停用: " + equityAccountCode);
        }
        if (equityAccount.getCategory() != AccountCategory.EQUITY) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ACCOUNT_NOT_EQUITY",
                    "承接损益的科目类别必须是所有者权益(EQUITY): " + equityAccountCode);
        }

        List<AccountPeriodBalanceTotal> totals = voucherRepository
                .sumPostedProfitLossTotalsByAccountBetween(period.getStartDate(), period.getEndDate());

        TreeMap<String, AccountPeriodBalanceTotal> totalsByAccount = new TreeMap<>();
        for (AccountPeriodBalanceTotal total : totals) {
            totalsByAccount.put(total.accountCode(), total);
        }

        List<JournalEntry> clearingEntries = new ArrayList<>();
        for (AccountPeriodBalanceTotal total : totalsByAccount.values()) {
            String accountCode = total.accountCode();
            BigDecimal debitTotal = scale(total.debitTotal());
            BigDecimal creditTotal = scale(total.creditTotal());
            BigDecimal balance = debitTotal.subtract(creditTotal);
            if (balance.compareTo(ZERO) == 0) {
                continue;
            }
            if (balance.compareTo(ZERO) > 0) {
                clearingEntries.add(new JournalEntry(clearingEntries.size() + 1, accountCode,
                        Direction.CREDIT, balance, null));
            } else {
                clearingEntries.add(new JournalEntry(clearingEntries.size() + 1, accountCode,
                        Direction.DEBIT, balance.negate(), null));
            }
        }

        if (clearingEntries.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFIT_LOSS_ALREADY_CLEARED",
                    "期间损益科目已全部结清，无需损益结转: " + normalizedPeriodCode);
        }

        BigDecimal debitTotal = ZERO;
        BigDecimal creditTotal = ZERO;
        for (JournalEntry entry : clearingEntries) {
            if (entry.getDirection() == Direction.DEBIT) {
                debitTotal = debitTotal.add(entry.getAmount());
            } else {
                creditTotal = creditTotal.add(entry.getAmount());
            }
        }
        BigDecimal equityAmount = creditTotal.subtract(debitTotal);
        if (equityAmount.compareTo(ZERO) > 0) {
            debitTotal = debitTotal.add(equityAmount);
            clearingEntries.add(new JournalEntry(clearingEntries.size() + 1, equityAccountCode,
                    Direction.DEBIT, equityAmount, null));
        } else if (equityAmount.compareTo(ZERO) < 0) {
            creditTotal = creditTotal.add(equityAmount.negate());
            clearingEntries.add(new JournalEntry(clearingEntries.size() + 1, equityAccountCode,
                    Direction.CREDIT, equityAmount.negate(), null));
        }

        String bizKey = "PL-CARRY-FORWARD-" + normalizedPeriodCode;
        String summary = "损益结转 " + normalizedPeriodCode;
        JournalVoucher voucher = new JournalVoucher(bizKey, period.getEndDate(), summary,
                debitTotal, creditTotal, "CARRY_FORWARD|" + normalizedPeriodCode + '|' + equityAccountCode);
        voucher.markAsProfitLossCarryForward(normalizedPeriodCode, equityAccountCode);
        for (JournalEntry entry : clearingEntries) {
            voucher.addEntry(entry);
        }

        try {
            voucherRepository.save(voucher);
            voucher.assignVoucherNo();
            voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            JournalVoucher concurrent = voucherRepository
                    .findByCarryForwardPeriodCode(normalizedPeriodCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "PROFIT_LOSS_CARRY_FORWARD_CONFLICT",
                            "该期间已存在损益结转凭证: " + normalizedPeriodCode));
            if (concurrent.getCarryForwardEquityAccountCode().equals(equityAccountCode)) {
                return VoucherResponse.from(concurrent);
            }
            throw new ApiException(HttpStatus.CONFLICT, "PROFIT_LOSS_CARRY_FORWARD_CONFLICT",
                    "该期间已使用其他承接科目完成损益结转: " + normalizedPeriodCode
                            + " 已使用科目: " + concurrent.getCarryForwardEquityAccountCode());
        }
        return VoucherResponse.from(voucher);
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
