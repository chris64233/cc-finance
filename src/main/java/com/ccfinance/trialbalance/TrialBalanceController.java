package com.ccfinance.trialbalance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ccfinance.trialbalance.dto.TrialBalanceResponse;

@RestController
@RequestMapping("/api/reports/trial-balance")
public class TrialBalanceController {

    private final TrialBalanceService trialBalanceService;

    public TrialBalanceController(TrialBalanceService trialBalanceService) {
        this.trialBalanceService = trialBalanceService;
    }

    @GetMapping("/{periodCode}")
    public TrialBalanceResponse getByPeriod(@PathVariable String periodCode) {
        return trialBalanceService.getByPeriod(periodCode);
    }
}
