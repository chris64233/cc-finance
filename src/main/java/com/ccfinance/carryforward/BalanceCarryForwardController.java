package com.ccfinance.carryforward;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ccfinance.voucher.dto.VoucherResponse;

@RestController
@RequestMapping("/api/periods/{periodCode}/balance-carry-forward")
public class BalanceCarryForwardController {

    private final BalanceCarryForwardService carryForwardService;

    public BalanceCarryForwardController(BalanceCarryForwardService carryForwardService) {
        this.carryForwardService = carryForwardService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherResponse carryForward(@PathVariable String periodCode) {
        return carryForwardService.carryForward(periodCode);
    }
}
