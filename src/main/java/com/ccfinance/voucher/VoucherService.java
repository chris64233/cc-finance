package com.ccfinance.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.common.ApiException;
import com.ccfinance.period.PeriodService;
import com.ccfinance.voucher.dto.EntryRequest;
import com.ccfinance.voucher.dto.ReversalRequest;
import com.ccfinance.voucher.dto.VoucherRequest;
import com.ccfinance.voucher.dto.VoucherResponse;

@Service
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final AccountRepository accountRepository;
    private final PeriodService periodService;

    public VoucherService(VoucherRepository voucherRepository, AccountRepository accountRepository,
            PeriodService periodService) {
        this.voucherRepository = voucherRepository;
        this.accountRepository = accountRepository;
        this.periodService = periodService;
    }

    @Transactional
    public VoucherResponse post(VoucherRequest request) {
        String bizKey = request.bizKey().trim();
        String fingerprint = fingerprint(request);

        var existing = voucherRepository.findByBizKey(bizKey);
        if (existing.isPresent()) {
            JournalVoucher voucher = existing.get();
            if (voucher.getRequestFingerprint().equals(fingerprint)) {
                return toResponse(voucher);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在且请求内容不一致: " + bizKey);
        }

        periodService.requireOpenPeriodForUpdate(request.voucherDate());

        // 按科目编码排序后逐行加悲观写锁：与科目停用串行化，且避免多科目凭证之间死锁。
        List<String> accountCodes = request.entries().stream()
                .map(entry -> entry.accountCode().trim())
                .distinct()
                .sorted()
                .toList();
        Map<String, Account> accountsByCode = new HashMap<>();
        for (String accountCode : accountCodes) {
            Account account = accountRepository.findByCodeForUpdate(accountCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "ACCOUNT_NOT_FOUND", "科目不存在: " + accountCode));
            accountsByCode.put(accountCode, account);
        }

        BigDecimal debitTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal creditTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (EntryRequest entry : request.entries()) {
            String accountCode = entry.accountCode().trim();
            Account account = accountsByCode.get(accountCode);
            if (!account.isEnabled()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ACCOUNT_DISABLED", "科目已停用: " + accountCode);
            }
            BigDecimal amount = entry.amount().setScale(2, RoundingMode.UNNECESSARY);
            if (entry.direction() == Direction.DEBIT) {
                debitTotal = debitTotal.add(amount);
            } else {
                creditTotal = creditTotal.add(amount);
            }
        }

        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VOUCHER_NOT_BALANCED",
                    "借方合计与贷方合计不相等: 借方=" + debitTotal + ", 贷方=" + creditTotal);
        }

        JournalVoucher voucher = new JournalVoucher(bizKey, request.voucherDate(),
                request.summary(), debitTotal, creditTotal, fingerprint);
        int lineNo = 1;
        for (EntryRequest entry : request.entries()) {
            voucher.addEntry(new JournalEntry(lineNo++, entry.accountCode().trim(),
                    entry.direction(), entry.amount().setScale(2, RoundingMode.UNNECESSARY),
                    entry.summary()));
        }

        try {
            voucherRepository.save(voucher);
            voucher.assignVoucherNo();
            voucherRepository.saveAndFlush(voucher);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在: " + bizKey);
        }
        return toResponse(voucher);
    }

    @Transactional
    public VoucherResponse reverse(String voucherNo, ReversalRequest request) {
        JournalVoucher original = voucherRepository.findByVoucherNo(voucherNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND",
                        "凭证不存在: " + voucherNo));
        if (original.getReversalOfVoucherNo() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_NOT_ALLOWED",
                    "冲销凭证不能再次冲销: " + voucherNo);
        }

        String bizKey = request.bizKey().trim();
        String fingerprint = fingerprint(voucherNo, request);
        var existing = voucherRepository.findByBizKey(bizKey);
        if (existing.isPresent()) {
            JournalVoucher voucher = existing.get();
            if (voucher.getRequestFingerprint().equals(fingerprint)) {
                return toResponse(voucher);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在且请求内容不一致: " + bizKey);
        }

        if (voucherRepository.existsByReversalOfVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_REVERSED",
                    "原凭证已存在冲销凭证: " + voucherNo);
        }

        periodService.requireOpenPeriodForUpdate(request.voucherDate());

        String summary = (request.summary() == null || request.summary().isBlank())
                ? "冲销 " + voucherNo
                : request.summary();
        JournalVoucher reversal = new JournalVoucher(bizKey, request.voucherDate(),
                summary, original.getDebitTotal(), original.getCreditTotal(), fingerprint);
        reversal.markAsReversalOf(voucherNo);
        for (JournalEntry entry : original.getEntries()) {
            Direction reversedDirection = entry.getDirection() == Direction.DEBIT
                    ? Direction.CREDIT
                    : Direction.DEBIT;
            reversal.addEntry(new JournalEntry(entry.getLineNo(), entry.getAccountCode(),
                    reversedDirection, entry.getAmount(), entry.getSummary()));
        }

        try {
            voucherRepository.save(reversal);
            reversal.assignVoucherNo();
            voucherRepository.saveAndFlush(reversal);
        } catch (DataIntegrityViolationException ex) {
            if (voucherRepository.existsByReversalOfVoucherNo(voucherNo)) {
                throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_REVERSED",
                        "原凭证已存在冲销凭证: " + voucherNo);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在: " + bizKey);
        }
        return toResponse(reversal);
    }

    @Transactional(readOnly = true)
    public VoucherResponse getByVoucherNo(String voucherNo) {
        return voucherRepository.findByVoucherNo(voucherNo)
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND",
                        "凭证不存在: " + voucherNo));
    }

    private VoucherResponse toResponse(JournalVoucher voucher) {
        String reversedBy = voucherRepository.findByReversalOfVoucherNo(voucher.getVoucherNo())
                .map(JournalVoucher::getVoucherNo)
                .orElse(null);
        return VoucherResponse.from(voucher, reversedBy);
    }

    private String fingerprint(String voucherNo, ReversalRequest request) {
        String canonical = "REVERSAL|" + request.bizKey().trim() + '|'
                + voucherNo + '|'
                + request.voucherDate() + '|'
                + (request.summary() == null ? "" : request.summary());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String fingerprint(VoucherRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.bizKey().trim()).append('|')
                .append(request.voucherDate()).append('|')
                .append(request.summary() == null ? "" : request.summary());
        for (EntryRequest entry : request.entries()) {
            canonical.append('|').append(entry.accountCode().trim())
                    .append(':').append(entry.direction())
                    .append(':').append(entry.amount().stripTrailingZeros().toPlainString())
                    .append(':').append(entry.summary() == null ? "" : entry.summary());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
