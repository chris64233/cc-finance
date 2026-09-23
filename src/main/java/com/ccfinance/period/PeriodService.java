package com.ccfinance.period;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.common.ApiException;
import com.ccfinance.period.dto.PeriodRequest;
import com.ccfinance.period.dto.PeriodResponse;
import com.ccfinance.period.dto.ReopenRequest;
import com.ccfinance.voucher.AccountPeriodBalanceTotal;
import com.ccfinance.voucher.VoucherRepository;

@Service
public class PeriodService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final PeriodRepository periodRepository;
    private final VoucherRepository voucherRepository;

    public PeriodService(PeriodRepository periodRepository, VoucherRepository voucherRepository) {
        this.periodRepository = periodRepository;
        this.voucherRepository = voucherRepository;
    }

    @Transactional
    public PeriodResponse create(PeriodRequest request) {
        AccountingPeriod period = new AccountingPeriod(request.year(), request.month());
        if (periodRepository.existsByPeriodCode(period.getPeriodCode())) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_ALREADY_EXISTS",
                    "会计期间已存在: " + period.getPeriodCode());
        }
        try {
            return PeriodResponse.from(periodRepository.saveAndFlush(period));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_ALREADY_EXISTS",
                    "会计期间已存在: " + period.getPeriodCode());
        }
    }

    @Transactional(readOnly = true)
    public List<PeriodResponse> list() {
        return periodRepository.findAll().stream()
                .sorted(Comparator.comparing(AccountingPeriod::getPeriodCode))
                .map(PeriodResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PeriodResponse getByCode(String periodCode) {
        return periodRepository.findByPeriodCode(periodCode)
                .map(PeriodResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + periodCode));
    }

    @Transactional
    public PeriodResponse close(String periodCode) {
        AccountingPeriod period = periodRepository.findByPeriodCodeForUpdate(periodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + periodCode));
        if (period.getStatus() == PeriodStatus.CLOSED) {
            return PeriodResponse.from(period);
        }
        requireClearedProfitLoss(period);
        period.close();
        return PeriodResponse.from(periodRepository.saveAndFlush(period));
    }

    @Transactional
    public PeriodResponse reopen(String periodCode, ReopenRequest request) {
        AccountingPeriod period = periodRepository.findByPeriodCodeForUpdate(periodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PERIOD_NOT_FOUND",
                        "会计期间不存在: " + periodCode));
        if (period.getStatus() == PeriodStatus.OPEN) {
            return PeriodResponse.from(period);
        }
        List<AccountingPeriod> laterClosedPeriods =
                periodRepository.findLaterClosedPeriodsForUpdate(periodCode);
        if (!laterClosedPeriods.isEmpty()) {
            String latest = laterClosedPeriods.stream()
                    .map(AccountingPeriod::getPeriodCode)
                    .max(String::compareTo)
                    .orElseThrow();
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_REOPEN_NOT_ALLOWED",
                    "只能重新打开最新的已关账期间，存在更晚的已关账期间: " + latest);
        }
        if (voucherRepository.findByBalanceCarryForwardPeriodCode(periodCode).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_REOPEN_NOT_ALLOWED",
                    "该期间已生成下期期初余额结转凭证，不能反关账: " + periodCode);
        }
        period.reopen(request.reason().trim());
        return PeriodResponse.from(periodRepository.saveAndFlush(period));
    }

    private void requireClearedProfitLoss(AccountingPeriod period) {
        List<String> unclearedAccounts = new ArrayList<>();
        for (AccountPeriodBalanceTotal totals : voucherRepository.sumPostedProfitLossTotalsByAccountBetween(
                period.getStartDate(), period.getEndDate())) {
            BigDecimal debitTotal = totals.debitTotal() == null ? ZERO : totals.debitTotal();
            BigDecimal creditTotal = totals.creditTotal() == null ? ZERO : totals.creditTotal();
            if (debitTotal.compareTo(creditTotal) != 0) {
                unclearedAccounts.add(totals.accountCode()
                        + " (借方累计=" + debitTotal + ", 贷方累计=" + creditTotal + ")");
            }
        }
        if (!unclearedAccounts.isEmpty()) {
            unclearedAccounts.sort(String::compareTo);
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_PROFIT_LOSS_NOT_CLEARED",
                    "期间内损益科目借贷余额未结清，不能关账: " + period.getPeriodCode()
                            + " " + String.join(", ", unclearedAccounts));
        }
    }

    @Transactional
    public void requireOpenPeriodForUpdate(LocalDate voucherDate) {
        String periodCode = YearMonth.from(voucherDate).toString();
        AccountingPeriod period = periodRepository.findByPeriodCodeForUpdate(periodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PERIOD_NOT_FOUND",
                        "凭证日期对应的会计期间不存在: " + periodCode));
        if (period.getStatus() == PeriodStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_CLOSED",
                    "会计期间已关账，禁止入账: " + periodCode);
        }
    }
}
