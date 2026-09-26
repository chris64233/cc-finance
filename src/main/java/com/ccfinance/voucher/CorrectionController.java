package com.ccfinance.voucher;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ccfinance.voucher.dto.CorrectionRequest;
import com.ccfinance.voucher.dto.CorrectionResponse;

import jakarta.validation.Valid;

@RestController
public class CorrectionController {

    private final CorrectionService correctionService;

    public CorrectionController(CorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    @PostMapping("/api/vouchers/{voucherNo}/correction")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse correct(@PathVariable String voucherNo,
            @Valid @RequestBody CorrectionRequest request) {
        return correctionService.correct(voucherNo, request);
    }

    @GetMapping("/api/vouchers/{voucherNo}/correction")
    public CorrectionResponse getByVoucherNo(@PathVariable String voucherNo) {
        return correctionService.getByOriginalVoucherNo(voucherNo);
    }

    @GetMapping("/api/corrections/{bizKey}")
    public CorrectionResponse getByBizKey(@PathVariable String bizKey) {
        return correctionService.getByBizKey(bizKey);
    }
}
