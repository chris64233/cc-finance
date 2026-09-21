package com.ccfinance.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.common.ApiException;
import com.ccfinance.period.dto.PeriodRequest;
import com.ccfinance.period.dto.PeriodResponse;
import com.ccfinance.voucher.AccountPeriodBalance;
import com.ccfinance.voucher.VoucherRepository;

@Service
public class PeriodService {

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
        List<String> unclearedAccounts = findUnclearedProfitLossAccounts(period);
        if (!unclearedAccounts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_PROFIT_LOSS_NOT_CLEARED",
                    "期间内损益科目余额未结清，无法关账: " + String.join(", ", unclearedAccounts));
        }
        period.close();
        return PeriodResponse.from(periodRepository.saveAndFlush(period));
    }

    private List<String> findUnclearedProfitLossAccounts(AccountingPeriod period) {
        return voucherRepository.sumPostedProfitLossBalancesByVoucherDateBetween(
                period.getStartDate(), period.getEndDate())
                .stream()
                .filter(balance -> debitTotal(balance).compareTo(creditTotal(balance)) != 0)
                .map(AccountPeriodBalance::accountCode)
                .toList();
    }

    private BigDecimal debitTotal(AccountPeriodBalance balance) {
        return balance.debitTotal() == null ? BigDecimal.ZERO : balance.debitTotal();
    }

    private BigDecimal creditTotal(AccountPeriodBalance balance) {
        return balance.creditTotal() == null ? BigDecimal.ZERO : balance.creditTotal();
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
