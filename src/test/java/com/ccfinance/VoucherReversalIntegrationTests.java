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
    void reverseVoucherCreatesPostedReversalWithFlippedEntries() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-001");

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-001", "reversalDate": "2026-09-20", "summary": "冲销错账"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").isNotEmpty())
                .andExpect(jsonPath("$.voucherNo").value(org.hamcrest.Matchers.not(voucherNo)))
                .andExpect(jsonPath("$.bizKey").value("REV-001"))
                .andExpect(jsonPath("$.voucherDate").value("2026-09-20"))
                .andExpect(jsonPath("$.summary").value("冲销错账"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.debitTotal").value(1000.50))
                .andExpect(jsonPath("$.creditTotal").value(1000.50))
                .andExpect(jsonPath("$.reversedVoucherNo").value(voucherNo))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1000.50))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(1000.50));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void queryVouchersShowsReversalAssociationAndOriginalStaysUnchanged() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-002");
        String reversalNo = reverseVoucher(voucherNo, "REV-002", "2026-09-20", null);

        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(voucherNo))
                .andExpect(jsonPath("$.reversalVoucherNo").value(reversalNo))
                .andExpect(jsonPath("$.reversedVoucherNo").doesNotExist())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.debitTotal").value(1000.50))
                .andExpect(jsonPath("$.creditTotal").value(1000.50))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));

        mockMvc.perform(get("/api/vouchers/" + reversalNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(reversalNo))
                .andExpect(jsonPath("$.reversedVoucherNo").value(voucherNo))
                .andExpect(jsonPath("$.reversalVoucherNo").doesNotExist());
    }

    @Test
    void reverseWithMissingPeriodReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-003");

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-003", "reversalDate": "2026-10-01"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/" + voucherNo + "/reversals"));

        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void reverseWithClosedPeriodReturns409AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-004");
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));
        mockMvc.perform(post("/api/periods/2026-10/close"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-004", "reversalDate": "2026-10-05"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void reverseIsIdempotentForSameBizKeyAndSameRequest() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-005");

        String body = """
                {"bizKey": "REV-005", "reversalDate": "2026-09-20", "summary": "冲销"}
                """;
        MvcResult first = mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstNo = com.jayway.jsonpath.JsonPath
                .read(first.getResponse().getContentAsString(), "$.voucherNo");
        String secondNo = com.jayway.jsonpath.JsonPath
                .read(second.getResponse().getContentAsString(), "$.voucherNo");

        assertThat(secondNo).isEqualTo(firstNo);
        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reverseWithSameBizKeyButDifferentContentReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-006");
        reverseVoucher(voucherNo, "REV-006", "2026-09-20", "冲销");

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-006", "reversalDate": "2026-09-21", "summary": "冲销"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_CONFLICT"));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reverseSameVoucherWithDifferentBizKeyReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-007");
        reverseVoucher(voucherNo, "REV-007", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-007-OTHER", "reversalDate": "2026-09-20"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_CONFLICT"));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reversalVoucherCannotBeReversedAgain() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        String voucherNo = postVoucher("BIZ-008");
        String reversalNo = reverseVoucher(voucherNo, "REV-008", "2026-09-20", null);

        mockMvc.perform(post("/api/vouchers/" + reversalNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-008-AGAIN", "reversalDate": "2026-09-21"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_REVERSIBLE"));

        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void reverseMissingVoucherReturns404() throws Exception {
        mockMvc.perform(post("/api/vouchers/JV-99999999/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-009", "reversalDate": "2026-09-20"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/vouchers/JV-99999999/reversals"));

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
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.50}
                                  ]
                                }
                                """.formatted(bizKey)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
    }

    private String reverseVoucher(String voucherNo, String bizKey, String reversalDate, String summary)
            throws Exception {
        String summaryPart = summary == null ? "" : ", \"summary\": \"%s\"".formatted(summary);
        MvcResult result = mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "%s", "reversalDate": "%s"%s}
                                """.formatted(bizKey, reversalDate, summaryPart)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
    }
}
