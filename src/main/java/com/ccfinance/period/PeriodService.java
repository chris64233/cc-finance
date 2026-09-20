package com.ccfinance.period;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.common.ApiException;
import com.ccfinance.period.dto.PeriodCreateRequest;
import com.ccfinance.period.dto.PeriodResponse;

@Service
public class PeriodService {

    private final AccountingPeriodRepository periodRepository;

    public PeriodService(AccountingPeriodRepository periodRepository) {
        this.periodRepository = periodRepository;
    }

    @Transactional
    public PeriodResponse create(PeriodCreateRequest request) {
        int year = request.year();
        int month = request.month();
        String periodCode = periodCodeOf(year, month);
        if (periodRepository.existsByPeriodCode(periodCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_ALREADY_EXISTS",
                    "会计期间已存在: " + periodCode);
        }
        YearMonth yearMonth = YearMonth.of(year, month);
        AccountingPeriod period = new AccountingPeriod(periodCode, year, month,
                yearMonth.atDay(1), yearMonth.atEndOfMonth());
        try {
            return PeriodResponse.from(periodRepository.saveAndFlush(period));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "PERIOD_ALREADY_EXISTS",
                    "会计期间已存在: " + periodCode);
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
        if (period.getStatus() == PeriodStatus.OPEN) {
            period.close();
            periodRepository.saveAndFlush(period);
        }
        return PeriodResponse.from(period);
    }

    public static String periodCodeOf(int year, int month) {
        return String.format("%04d-%02d", year, month);
    }
}
