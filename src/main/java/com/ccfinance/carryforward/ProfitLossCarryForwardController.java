package com.ccfinance.carryforward;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ccfinance.carryforward.dto.ProfitLossCarryForwardRequest;
import com.ccfinance.voucher.dto.VoucherResponse;

import jakarta.validation.Valid;

@RestController
public class ProfitLossCarryForwardController {

    private final ProfitLossCarryForwardService carryForwardService;

    public ProfitLossCarryForwardController(ProfitLossCarryForwardService carryForwardService) {
        this.carryForwardService = carryForwardService;
    }

    @PostMapping("/api/periods/{periodCode}/profit-loss-carry-forward")
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherResponse carryForward(@PathVariable String periodCode,
            @Valid @RequestBody ProfitLossCarryForwardRequest request) {
        return carryForwardService.carryForward(periodCode, request);
    }
}
