package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ccfinance.account.AccountRepository;
import com.ccfinance.period.AccountingPeriod;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.voucher.VoucherCorrectionRepository;
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class VoucherCorrectionIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private VoucherCorrectionRepository correctionRepository;

    @Autowired
    private PeriodRepository periodRepository;

    @BeforeEach
    void cleanDatabase() {
        correctionRepository.deleteAll();
        voucherRepository.deleteAll();
        accountRepository.deleteAll();
        periodRepository.deleteAll();
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 9));
    }

    @Test
    void correctCreatesReversalAndReplacementAtomicallyAndKeepsOriginal() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-001");

        MvcResult result = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-001", "2026-09-25", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bizKey").value("CORR-001"))
                .andExpect(jsonPath("$.originalVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.correctionDate").value("2026-09-25"))
                .andExpect(jsonPath("$.reversalVoucherNo").isNotEmpty())
                .andExpect(jsonPath("$.replacementVoucherNo").isNotEmpty())
                .andExpect(jsonPath("$.reversalVoucher.status").value("POSTED"))
                .andExpect(jsonPath("$.reversalVoucher.summary").value("冲销 " + originalNo))
                .andExpect(jsonPath("$.reversalVoucher.reversalOfVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversalVoucher.entries.length()").value(3))
                .andExpect(jsonPath("$.reversalVoucher.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.reversalVoucher.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.reversalVoucher.entries[0].amount").value(1000.50))
                .andExpect(jsonPath("$.reversalVoucher.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.reversalVoucher.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.reversalVoucher.entries[2].accountCode").value("2202"))
                .andExpect(jsonPath("$.reversalVoucher.entries[2].direction").value("DEBIT"))
                .andExpect(jsonPath("$.replacementVoucher.status").value("POSTED"))
                .andExpect(jsonPath("$.replacementVoucher.summary").value("更正 " + originalNo))
                .andExpect(jsonPath("$.replacementVoucher.debitTotal").value(1000.50))
                .andExpect(jsonPath("$.replacementVoucher.creditTotal").value(1000.50))
                .andExpect(jsonPath("$.replacementVoucher.entries.length()").value(2))
                .andExpect(jsonPath("$.replacementVoucher.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.replacementVoucher.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.replacementVoucher.entries[1].accountCode").value("2202"))
                .andExpect(jsonPath("$.replacementVoucher.entries[1].direction").value("CREDIT"))
                .andReturn();

        String reversalNo = JsonPath.read(result.getResponse().getContentAsString(), "$.reversalVoucherNo");
        String replacementNo = JsonPath.read(result.getResponse().getContentAsString(), "$.replacementVoucherNo");
        assertThat(reversalNo).isNotEqualTo(originalNo);
        assertThat(replacementNo).isNotEqualTo(originalNo);
        assertThat(reversalNo).isNotEqualTo(replacementNo);
        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(correctionRepository.count()).isEqualTo(1);

        // 原凭证保持不变，且能看到冲销关联。
        mockMvc.perform(get("/api/vouchers/" + originalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.reversedByVoucherNo").value(reversalNo));
    }

    @Test
    void correctionIsQueryableByOriginalVoucherNoAndBizKey() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-002");
        MvcResult created = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-002", "2026-09-25", "冲销错账", "更正错账")))
                .andExpect(status().isCreated())
                .andReturn();
        String reversalNo = JsonPath.read(created.getResponse().getContentAsString(), "$.reversalVoucherNo");
        String replacementNo = JsonPath.read(created.getResponse().getContentAsString(), "$.replacementVoucherNo");

        mockMvc.perform(get("/api/vouchers/" + originalNo + "/correction"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bizKey").value("CORR-002"))
                .andExpect(jsonPath("$.originalVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversalVoucherNo").value(reversalNo))
                .andExpect(jsonPath("$.replacementVoucherNo").value(replacementNo))
                .andExpect(jsonPath("$.reversalVoucher.summary").value("冲销错账"))
                .andExpect(jsonPath("$.replacementVoucher.summary").value("更正错账"));

        mockMvc.perform(get("/api/corrections/CORR-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversalVoucherNo").value(reversalNo))
                .andExpect(jsonPath("$.replacementVoucherNo").value(replacementNo));

        mockMvc.perform(get("/api/vouchers/JV-99999999/correction"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_FOUND"));
        mockMvc.perform(get("/api/corrections/CORR-UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_FOUND"));
    }

    @Test
    void correctIsIdempotentForSameBizKeyAndSameContent() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-003");
        String body = correctionBody("CORR-003", "2026-09-25", null, null);

        MvcResult first = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstReplacement = JsonPath.read(first.getResponse().getContentAsString(), "$.replacementVoucherNo");
        String secondReplacement = JsonPath.read(second.getResponse().getContentAsString(), "$.replacementVoucherNo");
        assertThat(secondReplacement).isEqualTo(firstReplacement);
        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(correctionRepository.count()).isEqualTo(1);
    }

    @Test
    void correctWithSameBizKeyButDifferentContentReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-004");
        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-004", "2026-09-25", null, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-004", "2026-09-26", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(correctionRepository.count()).isEqualTo(1);
    }

    @Test
    void correctSameOriginalWithNewBizKeyReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-005");
        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-005", "2026-09-25", null, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-005-AGAIN", "2026-09-26", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_CORRECTED"));

        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(correctionRepository.count()).isEqualTo(1);
    }

    @Test
    void reversalVoucherCannotBeCorrected() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-006");
        MvcResult reversal = mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-C-006", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String reversalNo = JsonPath.read(reversal.getResponse().getContentAsString(), "$.voucherNo");

        // 冲销凭证不能更正。
        mockMvc.perform(post("/api/vouchers/" + reversalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-006A", "2026-09-25", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_ALLOWED"));

        // 已被冲销的原凭证也不能更正。
        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-006B", "2026-09-25", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_REVERSED"));

        assertThat(voucherRepository.count()).isEqualTo(2);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void carryForwardVoucherCannotBeCorrected() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        createAccount("3001", "本年利润", "EQUITY", true);
        String originalNo = postVoucher("BIZ-C-007");
        MvcResult carryForward = mockMvc.perform(post("/api/periods/2026-09/profit-loss-carry-forward")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"equityAccountCode": "3001"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String carryForwardNo = JsonPath.read(carryForward.getResponse().getContentAsString(), "$.voucherNo");
        assertThat(carryForwardNo).isNotEqualTo(originalNo);

        mockMvc.perform(post("/api/vouchers/" + carryForwardNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-007", "2026-09-25", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_ALLOWED"));

        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void replacementVoucherCannotBeCorrectedAgain() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-008");
        MvcResult correction = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-008", "2026-09-25", null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        String replacementNo = JsonPath.read(correction.getResponse().getContentAsString(), "$.replacementVoucherNo");

        mockMvc.perform(post("/api/vouchers/" + replacementNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-008-AGAIN", "2026-09-26", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_ALLOWED"));

        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(correctionRepository.count()).isEqualTo(1);
    }

    @Test
    void correctWithClosedPeriodReturns409AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-009");
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));
        mockMvc.perform(post("/api/periods/2026-10/close"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-009", "2026-10-05", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void correctWithMissingPeriodReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-010");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-010", "2026-10-05", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void correctWithUnbalancedReplacementReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-011");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "CORR-011",
                                  "voucherDate": "2026-09-25",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50},
                                    {"accountCode": "2202", "direction": "CREDIT", "amount": 999.99}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_BALANCED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void correctWithDisabledAccountReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        createAccount("1002", "其他货币资金", "ASSET", false);
        String originalNo = postVoucher("BIZ-C-012");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "CORR-012",
                                  "voucherDate": "2026-09-25",
                                  "entries": [
                                    {"accountCode": "1002", "direction": "DEBIT", "amount": 1000.50},
                                    {"accountCode": "2202", "direction": "CREDIT", "amount": 1000.50}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void correctWithUnknownAccountReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-013");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "CORR-013",
                                  "voucherDate": "2026-09-25",
                                  "entries": [
                                    {"accountCode": "1999", "direction": "DEBIT", "amount": 1000.50},
                                    {"accountCode": "2202", "direction": "CREDIT", "amount": 1000.50}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void correctMissingVoucherReturns404() throws Exception {
        mockMvc.perform(post("/api/vouchers/JV-99999999/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("CORR-404", "2026-09-25", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/JV-99999999/correction"));

        assertThat(voucherRepository.count()).isZero();
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void correctWithInvalidRequestReturns400() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-014");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "", "voucherDate": "2026-09-25", "entries": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(correctionRepository.count()).isZero();
    }

    @Test
    void concurrentCorrectionsOfSameOriginalProduceExactlyOneCorrection() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                correctionRepository.deleteAll();
                voucherRepository.deleteAll();
                accountRepository.deleteAll();
                createAccount("1001", "银行存款", "ASSET", true);
                createAccount("6001", "主营业务收入", "REVENUE", true);
                createAccount("2202", "应付账款", "LIABILITY", true);
                String originalNo = postVoucher("BIZ-C-RACE-" + i);
                final String targetVoucherNo = originalNo;
                final String bizKeyA = "CORR-RACE-A-" + i;
                final String bizKeyB = "CORR-RACE-B-" + i;

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> first = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/vouchers/" + targetVoucherNo + "/correction")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(correctionBody(bizKeyA, "2026-09-25", null, null)))
                            .andReturn();
                });
                Future<MvcResult> second = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/vouchers/" + targetVoucherNo + "/correction")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(correctionBody(bizKeyB, "2026-09-25", null, null)))
                            .andReturn();
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                int firstStatus = first.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                int secondStatus = second.get(30, TimeUnit.SECONDS).getResponse().getStatus();
                assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(201, 409);
                assertThat(voucherRepository.count()).isEqualTo(3);
                assertThat(correctionRepository.count()).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void createAccount(String code, String name, String category, boolean enabled) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "category": "%s", "enabled": %s}
                                """.formatted(code, name, category, enabled)))
                .andExpect(status().isCreated());
    }

    private String postVoucher(String bizKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "%s",
                                  "voucherDate": "2026-09-19",
                                  "summary": "销售回款",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50, "summary": "收款"},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 700.50},
                                    {"accountCode": "2202", "direction": "CREDIT", "amount": 300.00}
                                  ]
                                }
                                """.formatted(bizKey)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
    }

    private String correctionBody(String bizKey, String voucherDate,
            String reversalSummary, String replacementSummary) {
        String reversalPart = reversalSummary == null ? "" : ", \"reversalSummary\": \"" + reversalSummary + "\"";
        String replacementPart = replacementSummary == null ? "" : ", \"replacementSummary\": \"" + replacementSummary + "\"";
        return """
                {
                  "bizKey": "%s",
                  "voucherDate": "%s"%s%s,
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50},
                    {"accountCode": "2202", "direction": "CREDIT", "amount": 1000.50}
                  ]
                }
                """.formatted(bizKey, voucherDate, reversalPart, replacementPart);
    }
}
