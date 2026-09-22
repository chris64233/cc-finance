package com.ccfinance.carryforward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountCategory;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.carryforward.dto.ProfitLossCarryForwardRequest;
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
    private static final String BIZ_KEY_PREFIX = "PL-CARRY-FORWARD:";

    private final PeriodRepository periodRepository;
    private final AccountRepository accountRepository;
    private final VoucherRepository voucherRepository;

    public ProfitLossCarryForwardService(PeriodRepository periodRepository,
            AccountRepository accountRepository, VoucherRepository voucherRepository) {
        this.periodRepository = periodRepository;
        this.accountRepository = accountRepository;
        this.voucherRepository = voucherRepository;
    }

    @Transactional
    public VoucherResponse carryForward(String periodCode, ProfitLossCarryForwardRequest request) {
        String carryAccountCode = request.carryAccountCode().trim();

        AccountingPeriod period = periodRepository.findByPeriodCodeForUpdate(periodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + periodCode));
        if (period.getStatus() == PeriodStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_CLOSED",
                    "会计期间已关账，不能结转损益: " + periodCode);
        }

        JournalVoucher existing = voucherRepository
                .findByProfitLossCarryPeriodCode(periodCode)
                .orElse(null);
        if (existing != null) {
            if (existing.getProfitLossCarryAccountCode().equals(carryAccountCode)) {
                return VoucherResponse.from(existing);
            }
            throw new ApiException(HttpStatus.CONFLICT, "PROFIT_LOSS_CARRY_FORWARD_CONFLICT",
                    "该期间已使用其他承接科目生成损益结转凭证: " + periodCode
                            + "，已用承接科目: " + existing.getProfitLossCarryAccountCode());
        }

        Account carryAccount = accountRepository.findByCodeForUpdate(carryAccountCode)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ACCOUNT_NOT_FOUND", "承接损益的科目不存在: " + carryAccountCode));
        if (!carryAccount.isEnabled()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ACCOUNT_DISABLED", "承接损益的科目已停用: " + carryAccountCode);
        }
        if (carryAccount.getCategory() != AccountCategory.EQUITY) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PROFIT_LOSS_CARRY_ACCOUNT_INVALID",
                    "承接损益的科目类别必须为所有者权益(EQUITY): " + carryAccountCode
                            + "，当前类别: " + carryAccount.getCategory());
        }

        List<AccountBalance> balances = voucherRepository
                .sumPostedProfitLossTotalsByAccountBetween(period.getStartDate(), period.getEndDate())
                .stream()
                .map(total -> {
                    BigDecimal debitTotal = total.debitTotal() == null ? ZERO : total.debitTotal();
                    BigDecimal creditTotal = total.creditTotal() == null ? ZERO : total.creditTotal();
                    return new AccountBalance(total.accountCode(),
                            debitTotal.subtract(creditTotal).setScale(2, RoundingMode.HALF_UP));
                })
                .filter(balance -> balance.netDebit().compareTo(ZERO) != 0)
                .sorted(Comparator.comparing(AccountBalance::accountCode))
                .toList();

        if (balances.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFIT_LOSS_ALREADY_CLEARED",
                    "期间内损益科目已全部结清，无需结转: " + periodCode);
        }

        BigDecimal plDebitTotal = ZERO;
        BigDecimal plCreditTotal = ZERO;
        List<JournalEntry> entries = new ArrayList<>();
        int lineNo = 1;
        for (AccountBalance balance : balances) {
            BigDecimal amount = balance.netDebit().abs();
            Direction direction = balance.netDebit().signum() > 0
                    ? Direction.CREDIT
                    : Direction.DEBIT;
            if (direction == Direction.DEBIT) {
                plDebitTotal = plDebitTotal.add(amount);
            } else {
                plCreditTotal = plCreditTotal.add(amount);
            }
            entries.add(new JournalEntry(lineNo++, balance.accountCode(), direction, amount,
                    "结转损益至 " + carryAccountCode));
        }

        BigDecimal equityAmount = plDebitTotal.subtract(plCreditTotal).abs()
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal debitTotal = plDebitTotal;
        BigDecimal creditTotal = plCreditTotal;
        if (equityAmount.compareTo(ZERO) > 0) {
            Direction equityDirection = plDebitTotal.compareTo(plCreditTotal) > 0
                    ? Direction.CREDIT
                    : Direction.DEBIT;
            if (equityDirection == Direction.DEBIT) {
                debitTotal = debitTotal.add(equityAmount);
            } else {
                creditTotal = creditTotal.add(equityAmount);
            }
            entries.add(new JournalEntry(lineNo, carryAccountCode, equityDirection, equityAmount,
                    "承接 " + periodCode + " 期间损益"));
        }

        JournalVoucher voucher = new JournalVoucher(
                BIZ_KEY_PREFIX + periodCode,
                period.getEndDate(),
                "损益结转 " + periodCode,
                debitTotal, creditTotal,
                fingerprint(periodCode, carryAccountCode));
        for (JournalEntry entry : entries) {
            voucher.addEntry(entry);
        }
        voucher.markAsProfitLossCarryForward(periodCode, carryAccountCode);

        try {
            voucherRepository.save(voucher);
            voucher.assignVoucherNo();
            voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFIT_LOSS_CARRY_FORWARD_CONFLICT",
                    "该期间已存在损益结转凭证: " + periodCode);
        }
        return VoucherResponse.from(voucher);
    }

    private String fingerprint(String periodCode, String carryAccountCode) {
        String canonical = "PROFIT_LOSS_CARRY_FORWARD|" + periodCode + '|' + carryAccountCode;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record AccountBalance(String accountCode, BigDecimal netDebit) {
    }
}
