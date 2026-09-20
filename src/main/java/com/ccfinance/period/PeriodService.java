package com.ccfinance.period;

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

@Service
public class PeriodService {

    private final PeriodRepository periodRepository;

    public PeriodService(PeriodRepository periodRepository) {
        this.periodRepository = periodRepository;
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
        period.close();
        return PeriodResponse.from(periodRepository.saveAndFlush(period));
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
