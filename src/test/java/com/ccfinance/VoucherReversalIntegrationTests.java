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
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class VoucherReversalIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private PeriodRepository periodRepository;

    @BeforeEach
    void cleanDatabase() {
        voucherRepository.deleteAll();
        accountRepository.deleteAll();
        periodRepository.deleteAll();
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 9));
    }

    @Test
    void reverseVoucherCreatesPostedReversalWithFlippedDirectionsInSameOrder() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-001");

        MvcResult result = mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-001", "voucherDate": "2026-09-20", "summary": "冲销销售回款"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").isNotEmpty())
                .andExpect(jsonPath("$.bizKey").value("REV-001"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.summary").value("冲销销售回款"))
                .andExpect(jsonPath("$.debitTotal").value(1000.50))
                .andExpect(jsonPath("$.creditTotal").value(1000.50))
                .andExpect(jsonPath("$.reversalOfVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversedByVoucherNo").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1000.50))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(700.50))
                .andExpect(jsonPath("$.entries[2].accountCode").value("2202"))
                .andExpect(jsonPath("$.entries[2].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(300.00))
                .andReturn();

        String reversalNo = JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
        assertThat(reversalNo).isNotEqualTo(originalNo);
        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reversalUsesDefaultSummaryWhenOmitted() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-002");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-002", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary").value("冲销 " + originalNo));
    }

    @Test
    void queryingOriginalAndReversalShowsAssociation() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-003");
        String reversalNo = reverse(originalNo, "REV-003", "2026-09-20", null);

        mockMvc.perform(get("/api/vouchers/" + originalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversalOfVoucherNo").doesNotExist())
                .andExpect(jsonPath("$.reversedByVoucherNo").value(reversalNo))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));

        mockMvc.perform(get("/api/vouchers/" + reversalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(reversalNo))
                .andExpect(jsonPath("$.reversalOfVoucherNo").value(originalNo))
                .andExpect(jsonPath("$.reversedByVoucherNo").doesNotExist());
    }

    @Test
    void reverseWithMissingPeriodReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-004");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-004", "voucherDate": "2026-10-01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/" + originalNo + "/reversal"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherRepository.findByBizKey("REV-004")).isEmpty();
    }

    @Test
    void reverseWithClosedPeriodReturns409AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-005");
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));
        mockMvc.perform(post("/api/periods/2026-10/close"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-005", "voucherDate": "2026-10-05"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
        assertThat(voucherRepository.findByBizKey("REV-005")).isEmpty();
    }

    @Test
    void reverseIsIdempotentForSameBizKeyAndSameRequest() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-006");

        String body = """
                {"bizKey": "REV-006", "voucherDate": "2026-09-20", "summary": "冲销"}
                """;
        MvcResult first = mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstNo = JsonPath.read(first.getResponse().getContentAsString(), "$.voucherNo");
        String secondNo = JsonPath.read(second.getResponse().getContentAsString(), "$.voucherNo");
        assertThat(secondNo).isEqualTo(firstNo);
        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reverseWithSameBizKeyButDifferentContentReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-007");
        reverse(originalNo, "REV-007", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-007", "voucherDate": "2026-09-21", "summary": "不同的请求"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reverseSameOriginalWithDifferentBizKeyReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-008");
        reverse(originalNo, "REV-008", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-008-OTHER", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_ALREADY_REVERSED"));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reversalVoucherCannotBeReversedAgain() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        String originalNo = postVoucher("BIZ-R-009");
        String reversalNo = reverse(originalNo, "REV-009", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + reversalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-009-AGAIN", "voucherDate": "2026-09-21"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_ALLOWED"));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reverseMissingVoucherReturns404() throws Exception {
        mockMvc.perform(post("/api/vouchers/JV-99999999/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-404", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/vouchers/JV-99999999/reversal"));

        assertThat(voucherRepository.count()).isZero();
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

    private String reverse(String voucherNo, String bizKey, String voucherDate, String summary) throws Exception {
        String summaryPart = summary == null ? "" : ", \"summary\": \"" + summary + "\"";
        MvcResult result = mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "%s", "voucherDate": "%s"%s}
                                """.formatted(bizKey, voucherDate, summaryPart)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
    }
}
