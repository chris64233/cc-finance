package com.ccfinance.voucher;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.common.BusinessException;
import com.ccfinance.common.ErrorCode;
import com.ccfinance.voucher.dto.PostVoucherRequest;
import com.ccfinance.voucher.dto.VoucherEntryRequest;
import com.ccfinance.voucher.dto.VoucherResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JournalVoucherService {

    private final JournalVoucherRepository voucherRepository;
    private final AccountRepository accountRepository;

    public JournalVoucherService(JournalVoucherRepository voucherRepository,
                                 AccountRepository accountRepository) {
        this.voucherRepository = voucherRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public VoucherResponse post(PostVoucherRequest request) {
        String bizId = request.bizId().trim();
        String requestHash = fingerprint(request);

        Optional<JournalVoucher> existing = voucherRepository.findByBizId(bizId);
        if (existing.isPresent()) {
            return resolveDuplicate(existing.get(), requestHash, bizId);
        }

        validateEntries(request);

        JournalVoucher voucher = new JournalVoucher(bizId, request.voucherDate(),
                request.summary(), requestHash);
        for (VoucherEntryRequest entryRequest : request.entries()) {
            voucher.addEntry(new VoucherEntry(entryRequest.accountCode().trim(),
                    entryRequest.direction(), entryRequest.amount(), entryRequest.summary()));
        }
        voucher.computeTotals();

        try {
            JournalVoucher saved = voucherRepository.saveAndFlush(voucher);
            saved.assignVoucherNo("JV-" + String.format("%08d", saved.getId()));
            return VoucherResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            // 并发下业务唯一号冲突，回查已有凭证并按幂等规则处理
            return voucherRepository.findByBizId(bizId)
                    .map(v -> resolveDuplicate(v, requestHash, bizId))
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public VoucherResponse getByVoucherNo(String voucherNo) {
        JournalVoucher voucher = voucherRepository.findByVoucherNo(voucherNo.trim())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ErrorCode.VOUCHER_NOT_FOUND,
                        "凭证不存在: " + voucherNo.trim()));
        return VoucherResponse.from(voucher);
    }

    private VoucherResponse resolveDuplicate(JournalVoucher existing, String requestHash, String bizId) {
        if (existing.getRequestHash().equals(requestHash)) {
            return VoucherResponse.from(existing);
        }
        throw new BusinessException(HttpStatus.CONFLICT, ErrorCode.BIZ_ID_CONFLICT,
                "业务唯一号已存在且请求内容不一致: " + bizId);
    }

    private void validateEntries(PostVoucherRequest request) {
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        Set<String> accountCodes = new LinkedHashSet<>();
        for (VoucherEntryRequest entry : request.entries()) {
            if (entry.amount().scale() > 2) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                        "金额最多保留两位小数: " + entry.amount());
            }
            accountCodes.add(entry.accountCode().trim());
            if (entry.direction() == EntryDirection.DEBIT) {
                debitTotal = debitTotal.add(entry.amount());
            } else {
                creditTotal = creditTotal.add(entry.amount());
            }
        }

        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VOUCHER_UNBALANCED,
                    "借方合计与贷方合计不相等: 借方=" + debitTotal + ", 贷方=" + creditTotal);
        }

        Map<String, Account> accounts = accountRepository.findByCodeIn(accountCodes).stream()
                .collect(Collectors.toMap(Account::getCode, Function.identity()));
        for (String code : accountCodes) {
            Account account = accounts.get(code);
            if (account == null) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.ACCOUNT_NOT_FOUND,
                        "科目不存在: " + code);
            }
            if (!account.isEnabled()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.ACCOUNT_DISABLED,
                        "科目已停用: " + code);
            }
        }
    }

    /**
     * 对请求内容生成稳定指纹，用于幂等冲突判断。
     */
    private String fingerprint(PostVoucherRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.voucherDate()).append('|')
                .append(normalize(request.summary())).append('|');
        for (VoucherEntryRequest entry : request.entries()) {
            canonical.append(entry.accountCode().trim()).append('|')
                    .append(entry.direction()).append('|')
                    .append(entry.amount().stripTrailingZeros().toPlainString()).append('|')
                    .append(normalize(entry.summary())).append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
