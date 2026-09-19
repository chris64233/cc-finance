package com.ccfinance.voucher;

import com.ccfinance.voucher.dto.PostVoucherRequest;
import com.ccfinance.voucher.dto.VoucherResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vouchers")
public class JournalVoucherController {

    private final JournalVoucherService voucherService;

    public JournalVoucherController(JournalVoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @PostMapping
    public ResponseEntity<VoucherResponse> post(@Valid @RequestBody PostVoucherRequest request) {
        VoucherResponse response = voucherService.post(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{voucherNo}")
    public VoucherResponse getByVoucherNo(@PathVariable String voucherNo) {
        return voucherService.getByVoucherNo(voucherNo);
    }
}
