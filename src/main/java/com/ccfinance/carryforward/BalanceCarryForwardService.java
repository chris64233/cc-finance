package com.ccfinance.carryforward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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
    public VoucherResponse carryForward(String periodCode) {
        String normalizedPeriodCode = periodCode.trim();

        AccountingPeriod sourcePeriod = periodRepository
                .findByPeriodCodeForUpdate(normalizedPeriodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + normalizedPeriodCode));
        if (sourcePeriod.getStatus() != PeriodStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_NOT_CLOSED",
                    "会计期间尚未关账，不能余额结转: " + normalizedPeriodCode);
        }

        var existing = voucherRepository.findByBalanceCarryForwardPeriodCode(normalizedPeriodCode);
        if (existing.isPresent()) {
            return VoucherResponse.from(existing.get());
        }

        String targetPeriodCode = YearMonth.of(sourcePeriod.getPeriodYear(), sourcePeriod.getPeriodMonth())
                .plusMonths(1)
                .toString();
        AccountingPeriod targetPeriod = periodRepository.findByPeriodCodeForUpdate(targetPeriodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "TARGET_PERIOD_NOT_FOUND",
                        "下一个自然月的会计期间不存在: " + targetPeriodCode));
        if (targetPeriod.getStatus() != PeriodStatus.OPEN) {
            throw new ApiException(HttpStatus.CONFLICT, "TARGET_PERIOD_CLOSED",
                    "下一个自然月的会计期间已关账，不能写入期初凭证: " + targetPeriodCode);
        }

        List<AccountPeriodBalanceTotal> totals = voucherRepository
                .sumPostedBalanceSheetTotalsUpTo(sourcePeriod.getEndDate());

        List<JournalEntry> openingEntries = new ArrayList<>();
        BigDecimal debitTotal = ZERO;
        BigDecimal creditTotal = ZERO;
        for (AccountPeriodBalanceTotal total : totals) {
            BigDecimal debitAmount = scale(total.debitTotal());
            BigDecimal creditAmount = scale(total.creditTotal());
            BigDecimal balance = debitAmount.subtract(creditAmount);
            if (balance.compareTo(ZERO) == 0) {
                continue;
            }
            Direction direction = balance.compareTo(ZERO) > 0 ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = balance.abs();
            openingEntries.add(new JournalEntry(openingEntries.size() + 1, total.accountCode(),
                    direction, amount, null));
            if (direction == Direction.DEBIT) {
                debitTotal = debitTotal.add(amount);
            } else {
                creditTotal = creditTotal.add(amount);
            }
        }

        if (openingEntries.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_ALREADY_CLEARED",
                    "截至期末资产、负债和所有者权益科目余额全部为零，无需余额结转: "
                            + normalizedPeriodCode);
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_CARRY_FORWARD_NOT_BALANCED",
                    "期末资产负债表科目借贷余额不平衡，不能生成期初凭证: "
                            + normalizedPeriodCode
                            + " 借方合计=" + debitTotal + ", 贷方合计=" + creditTotal);
        }

        String bizKey = "BALANCE-CARRY-FORWARD-" + normalizedPeriodCode;
        String summary = "期初余额结转自 " + normalizedPeriodCode;
        JournalVoucher voucher = new JournalVoucher(bizKey, targetPeriod.getStartDate(), summary,
                debitTotal, creditTotal, "BALANCE-CARRY-FORWARD|" + normalizedPeriodCode);
        voucher.markAsBalanceCarryForward(normalizedPeriodCode);
        for (JournalEntry entry : openingEntries) {
            voucher.addEntry(entry);
        }

        try {
            voucherRepository.save(voucher);
            voucher.assignVoucherNo();
            voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            JournalVoucher concurrent = voucherRepository
                    .findByBalanceCarryForwardPeriodCode(normalizedPeriodCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                            "BALANCE_CARRY_FORWARD_CONFLICT",
                            "该期间已存在期初余额结转凭证: " + normalizedPeriodCode));
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
