package com.ccfinance.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.common.ApiException;
import com.ccfinance.period.PeriodService;
import com.ccfinance.voucher.dto.CorrectionRequest;
import com.ccfinance.voucher.dto.CorrectionResponse;
import com.ccfinance.voucher.dto.EntryRequest;
import com.ccfinance.voucher.dto.ReversalRequest;
import com.ccfinance.voucher.dto.VoucherRequest;
import com.ccfinance.voucher.dto.VoucherResponse;

@Service
public class VoucherService {

    private final VoucherRepository voucherRepository;
    private final VoucherCorrectionRepository voucherCorrectionRepository;
    private final AccountRepository accountRepository;
    private final PeriodService periodService;

    public VoucherService(VoucherRepository voucherRepository,
            VoucherCorrectionRepository voucherCorrectionRepository,
            AccountRepository accountRepository,
            PeriodService periodService) {
        this.voucherRepository = voucherRepository;
        this.voucherCorrectionRepository = voucherCorrectionRepository;
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

        requireEnabledAccountsForUpdate(request.entries().stream()
                .map(entry -> entry.accountCode().trim())
                .distinct()
                .sorted()
                .toList());

        BigDecimal debitTotal = requireBalancedTotals(request.entries());

        JournalVoucher voucher = new JournalVoucher(bizKey, request.voucherDate(),
                request.summary(), debitTotal, debitTotal, fingerprint);
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

        requireEnabledAccountsForUpdate(original.getEntries().stream()
                .map(JournalEntry::getAccountCode)
                .distinct()
                .sorted()
                .toList());

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

    @Transactional
    public CorrectionResponse correct(String voucherNo, CorrectionRequest request) {
        JournalVoucher original = voucherRepository.findByVoucherNo(voucherNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND",
                        "凭证不存在: " + voucherNo));
        if (original.getReversalOfVoucherNo() != null
                || original.getCarryForwardPeriodCode() != null
                || original.getBalanceCarryForwardPeriodCode() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTION_NOT_ALLOWED",
                    "冲销凭证和结转凭证不能作为更正对象: " + voucherNo);
        }

        String bizKey = request.bizKey().trim();
        String fingerprint = fingerprint(voucherNo, request);
        var existingCorrection = voucherCorrectionRepository.findByBizKey(bizKey);
        if (existingCorrection.isPresent()) {
            VoucherCorrection correction = existingCorrection.get();
            if (correction.getRequestFingerprint().equals(fingerprint)) {
                return CorrectionResponse.from(correction);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在且请求内容不一致: " + bizKey);
        }

        if (voucherCorrectionRepository.existsByOriginalVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_CORRECTED",
                    "原凭证已被更正，不能重复更正: " + voucherNo);
        }
        if (voucherRepository.existsByReversalOfVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_REVERSED",
                    "原凭证已存在冲销凭证，不能更正: " + voucherNo);
        }

        periodService.requireOpenPeriodForUpdate(request.voucherDate());

        requireEnabledAccountsForUpdate(Stream.concat(
                original.getEntries().stream().map(JournalEntry::getAccountCode),
                request.entries().stream().map(entry -> entry.accountCode().trim()))
                .distinct()
                .sorted()
                .toList());

        BigDecimal replacementTotal = requireBalancedTotals(request.entries());

        JournalVoucher reversal = new JournalVoucher(bizKey + "#REVERSAL", request.voucherDate(),
                "更正冲销 " + voucherNo, original.getDebitTotal(), original.getCreditTotal(), fingerprint);
        reversal.markAsReversalOf(voucherNo);
        for (JournalEntry entry : original.getEntries()) {
            Direction reversedDirection = entry.getDirection() == Direction.DEBIT
                    ? Direction.CREDIT
                    : Direction.DEBIT;
            reversal.addEntry(new JournalEntry(entry.getLineNo(), entry.getAccountCode(),
                    reversedDirection, entry.getAmount(), entry.getSummary()));
        }

        String replacementSummary = (request.summary() == null || request.summary().isBlank())
                ? "更正 " + voucherNo
                : request.summary();
        JournalVoucher replacement = new JournalVoucher(bizKey + "#REPLACEMENT", request.voucherDate(),
                replacementSummary, replacementTotal, replacementTotal, fingerprint);
        replacement.markAsCorrectionOf(voucherNo);
        int lineNo = 1;
        for (EntryRequest entry : request.entries()) {
            replacement.addEntry(new JournalEntry(lineNo++, entry.accountCode().trim(),
                    entry.direction(), entry.amount().setScale(2, RoundingMode.UNNECESSARY),
                    entry.summary()));
        }

        voucherRepository.save(reversal);
        reversal.assignVoucherNo();
        try {
            voucherRepository.saveAndFlush(reversal);
            voucherRepository.save(replacement);
            replacement.assignVoucherNo();
            voucherRepository.saveAndFlush(replacement);
            VoucherCorrection correction = new VoucherCorrection(bizKey, voucherNo,
                    reversal.getVoucherNo(), replacement.getVoucherNo(), fingerprint);
            voucherCorrectionRepository.saveAndFlush(correction);
            return CorrectionResponse.from(correction);
        } catch (DataIntegrityViolationException ex) {
            if (voucherCorrectionRepository.existsByOriginalVoucherNo(voucherNo)) {
                throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_CORRECTED",
                        "原凭证已被更正，不能重复更正: " + voucherNo);
            }
            if (voucherRepository.existsByReversalOfVoucherNo(voucherNo)) {
                throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_REVERSED",
                        "原凭证已存在冲销凭证，不能更正: " + voucherNo);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在: " + bizKey);
        }
    }

    @Transactional(readOnly = true)
    public CorrectionResponse getCorrection(String voucherNo) {
        if (!voucherRepository.existsByVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND",
                    "凭证不存在: " + voucherNo);
        }
        return voucherCorrectionRepository.findByAnyVoucherNo(voucherNo)
                .map(CorrectionResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CORRECTION_NOT_FOUND",
                        "凭证未参与任何更正: " + voucherNo));
    }

    private BigDecimal requireBalancedTotals(List<EntryRequest> entries) {
        BigDecimal debitTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal creditTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (EntryRequest entry : entries) {
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
        return debitTotal;
    }

    private void requireEnabledAccountsForUpdate(List<String> accountCodes) {
        for (String accountCode : accountCodes) {
            Account account = accountRepository.findByCodeForUpdate(accountCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "ACCOUNT_NOT_FOUND", "科目不存在: " + accountCode));
            if (!account.isEnabled()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ACCOUNT_DISABLED", "科目已停用: " + accountCode);
            }
        }
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
        appendEntries(canonical, request.entries());
        return sha256(canonical.toString());
    }

    private String fingerprint(String voucherNo, CorrectionRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("CORRECTION|").append(request.bizKey().trim()).append('|')
                .append(voucherNo).append('|')
                .append(request.voucherDate()).append('|')
                .append(request.summary() == null ? "" : request.summary());
        appendEntries(canonical, request.entries());
        return sha256(canonical.toString());
    }

    private void appendEntries(StringBuilder canonical, List<EntryRequest> entries) {
        for (EntryRequest entry : entries) {
            canonical.append('|').append(entry.accountCode().trim())
                    .append(':').append(entry.direction())
                    .append(':').append(entry.amount().stripTrailingZeros().toPlainString())
                    .append(':').append(entry.summary() == null ? "" : entry.summary());
        }
    }

    private String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
