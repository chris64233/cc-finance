package com.ccfinance.voucher.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherCorrection;

public record CorrectionResponse(
        String bizKey,
        String originalVoucherNo,
        String reversalVoucherNo,
        String replacementVoucherNo,
        LocalDate correctionDate,
        Instant createdAt,
        VoucherResponse reversalVoucher,
        VoucherResponse replacementVoucher) {

    public static CorrectionResponse from(VoucherCorrection correction, JournalVoucher reversal,
            JournalVoucher replacement) {
        return new CorrectionResponse(
                correction.getBizKey(),
                correction.getOriginalVoucherNo(),
                correction.getReversalVoucherNo(),
                correction.getReplacementVoucherNo(),
                correction.getCorrectionDate(),
                correction.getCreatedAt(),
                VoucherResponse.from(reversal),
                VoucherResponse.from(replacement));
    }
}
