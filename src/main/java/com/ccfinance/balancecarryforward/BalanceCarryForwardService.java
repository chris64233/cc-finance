package com.ccfinance.balancecarryforward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class BalanceCarryForwardService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final PeriodRepository periodRepository;
    private final VoucherRepository voucherRepository;

    public BalanceCarryForwardService(PeriodRepository periodRepository,
            VoucherRepository voucherRepository) {
        this.periodRepository = periodRepository;
        this.voucherRepository = voucherRepository;
    }

    @Transactional
    public VoucherResponse carryForward(String rawPeriodCode) {
        String sourcePeriodCode = rawPeriodCode.trim();

        // 按期间编码升序锁定源期间和目标期间，保证与反关账、目标期间关账使用同一稳定加锁顺序。
        AccountingPeriod sourcePeriod = periodRepository.findByPeriodCodeForUpdate(sourcePeriodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + sourcePeriodCode));
        if (sourcePeriod.getStatus() != PeriodStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_NOT_CLOSED",
                    "会计期间尚未关账，不能余额结转: " + sourcePeriodCode);
        }

        JournalVoucher existing = voucherRepository
                .findByBalanceCarryForwardPeriodCode(sourcePeriodCode)
                .orElse(null);
        if (existing != null) {
            return VoucherResponse.from(existing);
        }

        String targetPeriodCode = YearMonth.parse(sourcePeriodCode).plusMonths(1).toString();
        AccountingPeriod targetPeriod = periodRepository.findByPeriodCodeForUpdate(targetPeriodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PERIOD_NOT_FOUND",
                        "下一自然月会计期间不存在: " + targetPeriodCode));
        if (targetPeriod.getStatus() != PeriodStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_CLOSED",
                    "下一自然月会计期间已关账，不能写入期初凭证: " + targetPeriodCode);
        }

        List<AccountPeriodBalanceTotal> totals = voucherRepository
                .sumPostedBalanceSheetTotalsByAccountUpTo(sourcePeriod.getEndDate());

        TreeMap<String, AccountPeriodBalanceTotal> totalsByAccount = new TreeMap<>();
        for (AccountPeriodBalanceTotal total : totals) {
            totalsByAccount.put(total.accountCode(), total);
        }

        List<JournalEntry> openingEntries = new ArrayList<>();
        BigDecimal debitTotal = ZERO;
        BigDecimal creditTotal = ZERO;
        for (AccountPeriodBalanceTotal total : totalsByAccount.values()) {
            String accountCode = total.accountCode();
            BigDecimal accountDebit = scale(total.debitTotal());
            BigDecimal accountCredit = scale(total.creditTotal());
            BigDecimal balance = accountDebit.subtract(accountCredit);
            if (balance.compareTo(ZERO) == 0) {
                continue;
            }
            Direction direction = balance.compareTo(ZERO) > 0 ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = balance.abs();
            openingEntries.add(new JournalEntry(openingEntries.size() + 1, accountCode,
                    direction, amount, null));
            if (direction == Direction.DEBIT) {
                debitTotal = debitTotal.add(amount);
            } else {
                creditTotal = creditTotal.add(amount);
            }
        }

        if (openingEntries.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_ALREADY_CLEARED",
                    "截至该期间结束日资产、负债和所有者权益科目余额全部为零，无需余额结转: "
                            + sourcePeriodCode);
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_CARRY_FORWARD_NOT_BALANCED",
                    "截至该期间结束日资产负债表科目借贷余额不平衡，不能生成期初凭证: "
                            + sourcePeriodCode + " 借方=" + debitTotal + ", 贷方=" + creditTotal);
        }

        String bizKey = "BAL-CARRY-FORWARD-" + sourcePeriodCode;
        String summary = "期初余额结转 自" + sourcePeriodCode;
        JournalVoucher voucher = new JournalVoucher(bizKey, targetPeriod.getStartDate(), summary,
                debitTotal, creditTotal, "BALANCE_CARRY_FORWARD|" + sourcePeriodCode);
        voucher.markAsBalanceCarryForward(sourcePeriodCode);
        for (JournalEntry entry : openingEntries) {
            voucher.addEntry(entry);
        }

        try {
            voucherRepository.save(voucher);
            voucher.assignVoucherNo();
            voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            JournalVoucher concurrent = voucherRepository
                    .findByBalanceCarryForwardPeriodCode(sourcePeriodCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "BALANCE_CARRY_FORWARD_CONFLICT",
                            "该期间已存在期初余额结转凭证: " + sourcePeriodCode));
            return VoucherResponse.from(concurrent);
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
