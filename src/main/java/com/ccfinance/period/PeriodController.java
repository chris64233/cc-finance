package com.ccfinance.period;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ccfinance.period.dto.PeriodReopenRequest;
import com.ccfinance.period.dto.PeriodRequest;
import com.ccfinance.period.dto.PeriodResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/periods")
public class PeriodController {

    private final PeriodService periodService;

    public PeriodController(PeriodService periodService) {
        this.periodService = periodService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodResponse create(@Valid @RequestBody PeriodRequest request) {
        return periodService.create(request);
    }

    @GetMapping
    public List<PeriodResponse> list() {
        return periodService.list();
    }

    @GetMapping("/{periodCode}")
    public PeriodResponse getByCode(@PathVariable String periodCode) {
        return periodService.getByCode(periodCode);
    }

    @PostMapping("/{periodCode}/close")
    public PeriodResponse close(@PathVariable String periodCode) {
        return periodService.close(periodCode);
    }

    @PostMapping("/{periodCode}/reopen")
    public PeriodResponse reopen(@PathVariable String periodCode,
            @Valid @RequestBody PeriodReopenRequest request) {
        return periodService.reopen(periodCode, request);
    }
}
