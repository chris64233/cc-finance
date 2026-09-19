package com.ccfinance.voucher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JournalVoucherApiTests {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setUpAccounts() throws Exception {
        createAccount("V-1001", "银行存款", "ASSET", true);
        createAccount("V-6001", "主营业务收入", "INCOME", true);
        createAccount("V-1002", "已停用科目", "ASSET", false);
    }

    private void createAccount(String code, String name, String category, boolean enabled) throws Exception {
        String body = """
                {"code": "%s", "name": "%s", "category": "%s", "enabled": %s}
                """.formatted(code, name, category, enabled);
        // 测试共享同一个应用上下文与内存库，科目可能已由前一个用例创建（返回 409）
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String voucherBody(String bizId, String debitAccount, String creditAccount,
                               String debitAmount, String creditAmount) {
        return """
                {
                  "bizId": "%s",
                  "voucherDate": "2026-09-19",
                  "summary": "销售回款",
                  "entries": [
                    {"accountCode": "%s", "direction": "DEBIT", "amount": %s, "summary": "收款"},
                    {"accountCode": "%s", "direction": "CREDIT", "amount": %s}
                  ]
                }
                """.formatted(bizId, debitAccount, debitAmount, creditAccount, creditAmount);
    }

    @Test
    void postAndQueryVoucher() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-OK-1", "V-1001", "V-6001", "100.00", "100.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").isString())
                .andExpect(jsonPath("$.bizId").value("BIZ-OK-1"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.debitTotal").value(100.00))
                .andExpect(jsonPath("$.creditTotal").value(100.00))
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].accountCode").value("V-1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].accountCode").value("V-6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andReturn();

        String voucherNo = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(voucherNo))
                .andExpect(jsonPath("$.bizId").value("BIZ-OK-1"))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].lineNo").value(1))
                .andExpect(jsonPath("$.entries[1].lineNo").value(2));
    }

    @Test
    void unbalancedVoucherIsRejected() throws Exception {
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-UNBALANCED", "V-1001", "V-6001", "100.00", "99.99")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VOUCHER_UNBALANCED"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));
    }

    @Test
    void unknownAccountIsRejected() throws Exception {
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-UNKNOWN-ACCT", "V-1001", "NO-SUCH", "10.00", "10.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void disabledAccountIsRejected() throws Exception {
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-DISABLED-ACCT", "V-1002", "V-6001", "10.00", "10.00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void idempotentReplayReturnsSameVoucher() throws Exception {
        String body = voucherBody("BIZ-IDEM-1", "V-1001", "V-6001", "55.20", "55.20");
        MvcResult first = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String voucherNo = com.jayway.jsonpath.JsonPath.read(
                first.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").value(voucherNo))
                .andExpect(jsonPath("$.bizId").value("BIZ-IDEM-1"));

        // 重复提交不应产生新凭证：凭证号保持一致
        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(2)));
    }

    @Test
    void sameBizIdWithDifferentContentReturns409() throws Exception {
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-CONFLICT-1", "V-1001", "V-6001", "10.00", "10.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-CONFLICT-1", "V-1001", "V-6001", "20.00", "20.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIZ_ID_CONFLICT"));
    }

    @Test
    void queryMissingVoucherReturns404() throws Exception {
        mockMvc.perform(get("/api/vouchers/JV-99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers/JV-99999999"));
    }

    @Test
    void invalidAmountIsRejected() throws Exception {
        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-BAD-AMOUNT", "V-1001", "V-6001", "10.001", "10.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voucherBody("BIZ-ZERO-AMOUNT", "V-1001", "V-6001", "0", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void lessThanTwoEntriesIsRejected() throws Exception {
        String body = """
                {
                  "bizId": "BIZ-ONE-ENTRY",
                  "voucherDate": "2026-09-19",
                  "entries": [
                    {"accountCode": "V-1001", "direction": "DEBIT", "amount": 10.00}
                  ]
                }
                """;
        mockMvc.perform(post("/api/vouchers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
