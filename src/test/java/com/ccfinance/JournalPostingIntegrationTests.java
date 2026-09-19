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
import com.ccfinance.voucher.VoucherRepository;

@SpringBootTest
@AutoConfigureMockMvc
class JournalPostingIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @BeforeEach
    void cleanDatabase() {
        voucherRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void createAccountReturnsCreatedAccount() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "1001", "name": "银行存款", "category": "ASSET"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.name").value("银行存款"))
                .andExpect(jsonPath("$.category").value("ASSET"))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.name").value("银行存款"));
    }

    @Test
    void createAccountWithDuplicateCodeReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "1001", "name": "库存现金", "category": "ASSET"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/accounts"));

        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void createAccountWithBlankCodeReturns400() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "   ", "name": "银行存款", "category": "ASSET"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void postVoucherAndQueryByVoucherNo() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        MvcResult result = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-001",
                                  "voucherDate": "2026-09-19",
                                  "summary": "销售回款",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50, "summary": "收款"},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.50}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").isNotEmpty())
                .andExpect(jsonPath("$.bizKey").value("BIZ-001"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.debitTotal").value(1000.50))
                .andExpect(jsonPath("$.creditTotal").value(1000.50))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andReturn();

        String voucherNo = com.jayway.jsonpath.JsonPath
                .read(result.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(voucherNo))
                .andExpect(jsonPath("$.summary").value("销售回款"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"));
    }

    @Test
    void postUnbalancedVoucherReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-002",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 99.99}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_BALANCED"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void postVoucherWithUnknownAccountReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-003",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                                    {"accountCode": "9999", "direction": "CREDIT", "amount": 100.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void postVoucherWithDisabledAccountReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", false);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-004",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void postVoucherIsIdempotentForSameBizKeyAndSameRequest() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        String body = """
                {
                  "bizKey": "BIZ-005",
                  "voucherDate": "2026-09-19",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 200.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 200.00}
                  ]
                }
                """;

        MvcResult first = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstNo = com.jayway.jsonpath.JsonPath
                .read(first.getResponse().getContentAsString(), "$.voucherNo");
        String secondNo = com.jayway.jsonpath.JsonPath
                .read(second.getResponse().getContentAsString(), "$.voucherNo");

        assertThat(secondNo).isEqualTo(firstNo);
        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void postVoucherWithSameBizKeyButDifferentContentReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-006",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 200.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 200.00}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-006",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 300.00}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void getMissingVoucherReturns404() throws Exception {
        mockMvc.perform(get("/api/vouchers/JV-99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("VOUCHER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/vouchers/JV-99999999"));
    }

    @Test
    void postVoucherWithInvalidAmountReturns400() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-007",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 10.005},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 10.005}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

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
}
