package com.ccfinance.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

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

@Service
public class CorrectionService {

    private static final String REVERSAL_BIZ_KEY_SUFFIX = "#CORR-REV";
    private static final String REPLACEMENT_BIZ_KEY_SUFFIX = "#CORR-NEW";

    private final VoucherRepository voucherRepository;
    private final VoucherCorrectionRepository correctionRepository;
    private final AccountRepository accountRepository;
    private final PeriodService periodService;

    public CorrectionService(VoucherRepository voucherRepository,
            VoucherCorrectionRepository correctionRepository,
            AccountRepository accountRepository,
            PeriodService periodService) {
        this.voucherRepository = voucherRepository;
        this.correctionRepository = correctionRepository;
        this.accountRepository = accountRepository;
        this.periodService = periodService;
    }

    @Transactional
    public CorrectionResponse correct(String voucherNo, CorrectionRequest request) {
        String bizKey = request.bizKey().trim();
        String fingerprint = fingerprint(voucherNo, request);

        var existing = correctionRepository.findByBizKey(bizKey);
        if (existing.isPresent()) {
            VoucherCorrection correction = existing.get();
            if (correction.getRequestFingerprint().equals(fingerprint)) {
                return toResponse(correction);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在且请求内容不一致: " + bizKey);
        }

        // 先锁定原凭证行，串行化同一原凭证的并发更正。
        JournalVoucher original = voucherRepository.findByVoucherNoForUpdate(voucherNo)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VOUCHER_NOT_FOUND",
                        "凭证不存在: " + voucherNo));
        requireCorrectable(original);

        // 复用现有期间并发控制：更正与关账竞争时只会形成完整更正或完成关账其中一种结果。
        periodService.requireOpenPeriodForUpdate(request.voucherDate());

        TreeSet<String> accountCodes = new TreeSet<>();
        for (JournalEntry entry : original.getEntries()) {
            accountCodes.add(entry.getAccountCode());
        }
        for (EntryRequest entry : request.entries()) {
            accountCodes.add(entry.accountCode().trim());
        }
        requireEnabledAccountsForUpdate(List.copyOf(accountCodes));

        BigDecimal debitTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal creditTotal = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (EntryRequest entry : request.entries()) {
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

        String reversalSummary = (request.reversalSummary() == null || request.reversalSummary().isBlank())
                ? "冲销 " + voucherNo
                : request.reversalSummary();
        JournalVoucher reversal = new JournalVoucher(bizKey + REVERSAL_BIZ_KEY_SUFFIX, request.voucherDate(),
                reversalSummary, original.getDebitTotal(), original.getCreditTotal(),
                fingerprint("CORR-REV", voucherNo, request));
        reversal.markAsReversalOf(voucherNo);
        for (JournalEntry entry : original.getEntries()) {
            Direction reversedDirection = entry.getDirection() == Direction.DEBIT
                    ? Direction.CREDIT
                    : Direction.DEBIT;
            reversal.addEntry(new JournalEntry(entry.getLineNo(), entry.getAccountCode(),
                    reversedDirection, entry.getAmount(), entry.getSummary()));
        }

        String replacementSummary = (request.replacementSummary() == null || request.replacementSummary().isBlank())
                ? "更正 " + voucherNo
                : request.replacementSummary();
        JournalVoucher replacement = new JournalVoucher(bizKey + REPLACEMENT_BIZ_KEY_SUFFIX, request.voucherDate(),
                replacementSummary, debitTotal, creditTotal,
                fingerprint("CORR-NEW", voucherNo, request));
        int lineNo = 1;
        for (EntryRequest entry : request.entries()) {
            replacement.addEntry(new JournalEntry(lineNo++, entry.accountCode().trim(),
                    entry.direction(), entry.amount().setScale(2, RoundingMode.UNNECESSARY),
                    entry.summary()));
        }

        try {
            voucherRepository.save(reversal);
            reversal.assignVoucherNo();
            voucherRepository.saveAndFlush(reversal);
            voucherRepository.save(replacement);
            replacement.assignVoucherNo();
            VoucherCorrection correction = new VoucherCorrection(bizKey, voucherNo,
                    reversal.getVoucherNo(), replacement.getVoucherNo(),
                    request.voucherDate(), fingerprint);
            correctionRepository.saveAndFlush(correction);
            return toResponse(correction);
        } catch (DataIntegrityViolationException ex) {
            // 并发更正同一原凭证时只有最先提交的事务成功，失败方根据已形成的关联给出一致错误。
            if (correctionRepository.existsByOriginalVoucherNo(voucherNo)
                    || voucherRepository.existsByReversalOfVoucherNo(voucherNo)) {
                throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_CORRECTED",
                        "原凭证已完成更正，不能重复更正: " + voucherNo);
            }
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "业务唯一号已存在: " + bizKey);
        }
    }

    @Transactional(readOnly = true)
    public CorrectionResponse getByOriginalVoucherNo(String voucherNo) {
        return correctionRepository.findByOriginalVoucherNo(voucherNo)
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CORRECTION_NOT_FOUND",
                        "凭证不存在更正记录: " + voucherNo));
    }

    @Transactional(readOnly = true)
    public CorrectionResponse getByBizKey(String bizKey) {
        return correctionRepository.findByBizKey(bizKey.trim())
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CORRECTION_NOT_FOUND",
                        "更正记录不存在: " + bizKey));
    }

    private void requireCorrectable(JournalVoucher original) {
        String voucherNo = original.getVoucherNo();
        if (original.getReversalOfVoucherNo() != null
                || original.getCarryForwardPeriodCode() != null
                || original.getBalanceCarryForwardPeriodCode() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTION_NOT_ALLOWED",
                    "冲销凭证和结转凭证不能更正: " + voucherNo);
        }
        if (correctionRepository.existsByReplacementVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTION_NOT_ALLOWED",
                    "更正生成的替换凭证不能再次更正: " + voucherNo);
        }
        if (correctionRepository.existsByOriginalVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_CORRECTED",
                    "原凭证已完成更正，不能重复更正: " + voucherNo);
        }
        if (voucherRepository.existsByReversalOfVoucherNo(voucherNo)) {
            throw new ApiException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_REVERSED",
                    "原凭证已被冲销，不能更正: " + voucherNo);
        }
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

    private CorrectionResponse toResponse(VoucherCorrection correction) {
        JournalVoucher reversal = voucherRepository
                .findByVoucherNo(correction.getReversalVoucherNo())
                .orElseThrow(() -> new IllegalStateException(
                        "更正冲销凭证缺失: " + correction.getReversalVoucherNo()));
        JournalVoucher replacement = voucherRepository
                .findByVoucherNo(correction.getReplacementVoucherNo())
                .orElseThrow(() -> new IllegalStateException(
                        "更正替换凭证缺失: " + correction.getReplacementVoucherNo()));
        return CorrectionResponse.from(correction, reversal, replacement);
    }

    private String fingerprint(String voucherNo, CorrectionRequest request) {
        return fingerprint("CORRECTION", voucherNo, request);
    }

    private String fingerprint(String kind, String voucherNo, CorrectionRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(kind).append('|')
                .append(request.bizKey().trim()).append('|')
                .append(voucherNo).append('|')
                .append(request.voucherDate()).append('|')
                .append(request.reversalSummary() == null ? "" : request.reversalSummary()).append('|')
                .append(request.replacementSummary() == null ? "" : request.replacementSummary());
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
