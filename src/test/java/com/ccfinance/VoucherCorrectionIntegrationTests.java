package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private VoucherCorrectionRepository voucherCorrectionRepository;

    @Autowired
    private PeriodRepository periodRepository;

    @BeforeEach
    void cleanDatabase() {
        voucherCorrectionRepository.deleteAll();
        voucherRepository.deleteAll();
        accountRepository.deleteAll();
        periodRepository.deleteAll();
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 9));
    }

    @Test
    void correctVoucherCreatesReversalAndReplacementAtomically() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-001");

        MvcResult result = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-001", "2026-09-20", "更正销售回款")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bizKey").value("COR-001"))
                .andExpect(jsonPath("$.originalVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversalVoucherNo").isNotEmpty())
                .andExpect(jsonPath("$.replacementVoucherNo").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();

        String reversalNo = JsonPath.read(result.getResponse().getContentAsString(), "$.reversalVoucherNo");
        String replacementNo = JsonPath.read(result.getResponse().getContentAsString(), "$.replacementVoucherNo");
        assertThat(reversalNo).isNotEqualTo(originalNo);
        assertThat(replacementNo).isNotEqualTo(originalNo);
        assertThat(replacementNo).isNotEqualTo(reversalNo);
        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(voucherCorrectionRepository.count()).isEqualTo(1);

        // 原凭证保持不变
        mockMvc.perform(get("/api/vouchers/" + originalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("销售回款"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.correctionOfVoucherNo").doesNotExist());

        // 反向冲销凭证沿用原分录并交换借贷方向
        mockMvc.perform(get("/api/vouchers/" + reversalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.voucherDate").value("2026-09-20"))
                .andExpect(jsonPath("$.summary").value("更正冲销 " + originalNo))
                .andExpect(jsonPath("$.reversalOfVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.debitTotal").value(1000.50))
                .andExpect(jsonPath("$.creditTotal").value(1000.50))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1000.50))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[2].accountCode").value("2202"))
                .andExpect(jsonPath("$.entries[2].direction").value("DEBIT"));

        // 替换凭证按请求内容入账，并标记更正来源
        mockMvc.perform(get("/api/vouchers/" + replacementNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.voucherDate").value("2026-09-20"))
                .andExpect(jsonPath("$.summary").value("更正销售回款"))
                .andExpect(jsonPath("$.correctionOfVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.debitTotal").value(1200.00))
                .andExpect(jsonPath("$.creditTotal").value(1200.00))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1200.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));
    }

    @Test
    void correctionAssociationIsQueryableFromAllThreeVouchers() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-002");
        MvcResult result = correct(originalNo, "COR-002", "2026-09-20", null);
        String reversalNo = JsonPath.read(result.getResponse().getContentAsString(), "$.reversalVoucherNo");
        String replacementNo = JsonPath.read(result.getResponse().getContentAsString(), "$.replacementVoucherNo");

        for (String voucherNo : new String[] {originalNo, reversalNo, replacementNo}) {
            mockMvc.perform(get("/api/vouchers/" + voucherNo + "/correction"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bizKey").value("COR-002"))
                    .andExpect(jsonPath("$.originalVoucherNo").value(originalNo))
                    .andExpect(jsonPath("$.reversalVoucherNo").value(reversalNo))
                    .andExpect(jsonPath("$.replacementVoucherNo").value(replacementNo));
        }
    }

    @Test
    void getCorrectionForVoucherWithoutCorrectionReturns404() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-003");

        mockMvc.perform(get("/api/vouchers/" + originalNo + "/correction"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/" + originalNo + "/correction"));

        mockMvc.perform(get("/api/vouchers/JV-99999999/correction"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"));
    }

    @Test
    void correctIsIdempotentForSameBizKeyAndSameRequest() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-004");
        String body = correctionBody("COR-004", "2026-09-20", "更正");

        MvcResult first = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstJson = first.getResponse().getContentAsString();
        String secondJson = second.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(secondJson, "$.originalVoucherNo"))
                .isEqualTo(JsonPath.read(firstJson, "$.originalVoucherNo"));
        assertThat(JsonPath.<String>read(secondJson, "$.reversalVoucherNo"))
                .isEqualTo(JsonPath.read(firstJson, "$.reversalVoucherNo"));
        assertThat(JsonPath.<String>read(secondJson, "$.replacementVoucherNo"))
                .isEqualTo(JsonPath.read(firstJson, "$.replacementVoucherNo"));
        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(voucherCorrectionRepository.count()).isEqualTo(1);
    }

    @Test
    void correctWithSameBizKeyButDifferentContentReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-005");
        correct(originalNo, "COR-005", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-005", "2026-09-21", "不同的请求")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(voucherCorrectionRepository.count()).isEqualTo(1);
    }

    @Test
    void correctSameOriginalWithDifferentBizKeyReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-006");
        correct(originalNo, "COR-006", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-006-OTHER", "2026-09-20", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_CORRECTED"));

        assertThat(voucherRepository.count()).isEqualTo(3);
        assertThat(voucherCorrectionRepository.count()).isEqualTo(1);
    }

    @Test
    void reversedVoucherCannotBeCorrected() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-007");
        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-C-007", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-007", "2026-09-21", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_REVERSED"));

        assertThat(voucherRepository.count()).isEqualTo(2);
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void reversalVoucherCannotBeCorrected() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-008");
        MvcResult reversal = mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-C-008", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String reversalNo = JsonPath.read(reversal.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + reversalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-008", "2026-09-21", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_ALLOWED"));

        assertThat(voucherRepository.count()).isEqualTo(2);
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void carryForwardVoucherCannotBeCorrected() throws Exception {
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("3001", "本年利润", "EQUITY", true);
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-C-009",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "6001", "direction": "DEBIT", "amount": 500.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 500.00}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-C-009B",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 800.00},
                                    {"accountCode": "6001", "direction": "DEBIT", "amount": 300.00},
                                    {"accountCode": "3001", "direction": "DEBIT", "amount": 500.00}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());
        MvcResult carryForward = mockMvc.perform(post("/api/periods/2026-09/profit-loss-carry-forward")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"equityAccountCode": "3001"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String carryForwardNo = JsonPath.read(carryForward.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + carryForwardNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-009", "2026-09-28", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECTION_NOT_ALLOWED"));

        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void correctedVoucherCannotBeReversedAfterwards() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-010");
        correct(originalNo, "COR-010", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-C-010", "voucherDate": "2026-09-21"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_REVERSED"));

        assertThat(voucherRepository.count()).isEqualTo(3);
    }

    @Test
    void correctWithMissingPeriodReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-011");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-011", "2026-10-01", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/" + originalNo + "/correction"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void correctWithClosedPeriodReturns409AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-012");
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));
        mockMvc.perform(post("/api/periods/2026-10/close"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-012", "2026-10-05", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void correctWithUnbalancedReplacementReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-013");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "COR-013",
                                  "voucherDate": "2026-09-20",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1200.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1100.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_BALANCED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void correctWithDisabledAccountReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        createAccount("1002", "库存现金", "ASSET", false);
        String originalNo = postVoucher("BIZ-C-014");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "COR-014",
                                  "voucherDate": "2026-09-20",
                                  "entries": [
                                    {"accountCode": "1002", "direction": "DEBIT", "amount": 1200.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1200.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void correctMissingVoucherReturns404() throws Exception {
        mockMvc.perform(post("/api/vouchers/JV-99999999/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("COR-404", "2026-09-20", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/JV-99999999/correction"));

        assertThat(voucherRepository.count()).isZero();
        assertThat(voucherCorrectionRepository.count()).isZero();
    }

    @Test
    void correctWithInvalidRequestReturns400() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-C-015");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "", "voucherDate": "2026-09-20", "entries": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherCorrectionRepository.count()).isZero();
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

    private String correctionBody(String bizKey, String voucherDate, String summary) {
        String summaryPart = summary == null ? "" : ", \"summary\": \"" + summary + "\"";
        return """
                {
                  "bizKey": "%s",
                  "voucherDate": "%s"%s,
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1200.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1200.00}
                  ]
                }
                """.formatted(bizKey, voucherDate, summaryPart);
    }

    private MvcResult correct(String voucherNo, String bizKey, String voucherDate, String summary)
            throws Exception {
        return mockMvc.perform(post("/api/vouchers/" + voucherNo + "/correction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody(bizKey, voucherDate, summary)))
                .andExpect(status().isCreated())
                .andReturn();
    }
}
