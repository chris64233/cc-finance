package com.ccfinance.voucher.dto;

import java.time.Instant;

import com.ccfinance.voucher.VoucherCorrection;

public record CorrectionResponse(
        String bizKey,
        String originalVoucherNo,
        String reversalVoucherNo,
        String replacementVoucherNo,
        Instant createdAt) {

    public static CorrectionResponse from(VoucherCorrection correction) {
        return new CorrectionResponse(
                correction.getBizKey(),
                correction.getOriginalVoucherNo(),
                correction.getReversalVoucherNo(),
                correction.getReplacementVoucherNo(),
                correction.getCreatedAt());
    }
}
