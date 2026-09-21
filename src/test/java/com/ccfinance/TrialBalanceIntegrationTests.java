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

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountCategory;
import com.ccfinance.account.AccountRepository;
import com.ccfinance.period.AccountingPeriod;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class TrialBalanceIntegrationTests {

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
    void aggregatesMultipleVouchersAndAccountsSortedByAccountCode() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);

        postVoucher("""
                {
                  "bizKey": "BIZ-TB-001",
                  "voucherDate": "2026-09-02",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                  ]
                }
                """);
        postVoucher("""
                {
                  "bizKey": "BIZ-TB-002",
                  "voucherDate": "2026-09-15",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 500.50},
                    {"accountCode": "2202", "direction": "CREDIT", "amount": 500.50}
                  ]
                }
                """);

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.debitTotal").value(1500.50))
                .andExpect(jsonPath("$.creditTotal").value(1500.50))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].accountName").value("银行存款"))
                .andExpect(jsonPath("$.items[0].category").value("ASSET"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(1500.50))
                .andExpect(jsonPath("$.items[0].creditAmount").value(0.00))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].balanceAmount").value(1500.50))
                .andExpect(jsonPath("$.items[1].accountCode").value("2202"))
                .andExpect(jsonPath("$.items[1].accountName").value("应付账款"))
                .andExpect(jsonPath("$.items[1].category").value("LIABILITY"))
                .andExpect(jsonPath("$.items[1].debitAmount").value(0.00))
                .andExpect(jsonPath("$.items[1].creditAmount").value(500.50))
                .andExpect(jsonPath("$.items[1].balanceDirection").value("CREDIT"))
                .andExpect(jsonPath("$.items[1].balanceAmount").value(500.50))
                .andExpect(jsonPath("$.items[2].accountCode").value("6001"))
                .andExpect(jsonPath("$.items[2].debitAmount").value(0.00))
                .andExpect(jsonPath("$.items[2].creditAmount").value(1000.00))
                .andExpect(jsonPath("$.items[2].balanceDirection").value("CREDIT"))
                .andExpect(jsonPath("$.items[2].balanceAmount").value(1000.00));
    }

    @Test
    void computesDebitAndCreditBalancesForAccountsWithBothSides() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        postVoucher("""
                {
                  "bizKey": "BIZ-TB-010",
                  "voucherDate": "2026-09-03",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 300.00}
                  ]
                }
                """);
        postVoucher("""
                {
                  "bizKey": "BIZ-TB-011",
                  "voucherDate": "2026-09-04",
                  "entries": [
                    {"accountCode": "6001", "direction": "DEBIT", "amount": 100.00},
                    {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                  ]
                }
                """);

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(400.00))
                .andExpect(jsonPath("$.creditTotal").value(400.00))
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(300.00))
                .andExpect(jsonPath("$.items[0].creditAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].balanceAmount").value(200.00))
                .andExpect(jsonPath("$.items[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.items[1].debitAmount").value(100.00))
                .andExpect(jsonPath("$.items[1].creditAmount").value(300.00))
                .andExpect(jsonPath("$.items[1].balanceDirection").value("CREDIT"))
                .andExpect(jsonPath("$.items[1].balanceAmount").value(200.00));
    }

    @Test
    void returnsNoneDirectionAndZeroBalanceWhenSidesAreEqual() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        postVoucher("""
                {
                  "bizKey": "BIZ-TB-020",
                  "voucherDate": "2026-09-05",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 250.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 250.00}
                  ]
                }
                """);
        postVoucher("""
                {
                  "bizKey": "BIZ-TB-021",
                  "voucherDate": "2026-09-06",
                  "entries": [
                    {"accountCode": "1001", "direction": "CREDIT", "amount": 250.00},
                    {"accountCode": "6001", "direction": "DEBIT", "amount": 250.00}
                  ]
                }
                """);

        MvcResult result = mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(500.00))
                .andExpect(jsonPath("$.creditTotal").value(500.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.items[0].balanceAmount").value(0.00))
                .andExpect(jsonPath("$.items[1].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.items[1].balanceAmount").value(0.00))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"balanceAmount\":0.00")
                .contains("\"debitTotal\":500.00");
    }

    @Test
    void reversalInSamePeriodKeepsBothSidesAndOffsetsBalances() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createAccount("2202", "应付账款", "LIABILITY", true);

        MvcResult original = postVoucher("""
                {
                  "bizKey": "BIZ-TB-030",
                  "voucherDate": "2026-09-10",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 700.50},
                    {"accountCode": "2202", "direction": "CREDIT", "amount": 300.00}
                  ]
                }
                """);
        String originalNo = JsonPath.read(original.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-TB-030", "voucherDate": "2026-09-20", "summary": "冲销"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(2001.00))
                .andExpect(jsonPath("$.creditTotal").value(2001.00))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(1000.50))
                .andExpect(jsonPath("$.items[0].creditAmount").value(1000.50))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.items[0].balanceAmount").value(0.00))
                .andExpect(jsonPath("$.items[1].accountCode").value("2202"))
                .andExpect(jsonPath("$.items[1].debitAmount").value(300.00))
                .andExpect(jsonPath("$.items[1].creditAmount").value(300.00))
                .andExpect(jsonPath("$.items[1].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.items[1].balanceAmount").value(0.00))
                .andExpect(jsonPath("$.items[2].accountCode").value("6001"))
                .andExpect(jsonPath("$.items[2].debitAmount").value(700.50))
                .andExpect(jsonPath("$.items[2].creditAmount").value(700.50))
                .andExpect(jsonPath("$.items[2].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.items[2].balanceAmount").value(0.00));
    }

    @Test
    void trialBalanceCanBeQueriedForClosedPeriodAndPostingStillRejected() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        postVoucher("""
                {
                  "bizKey": "BIZ-TB-040",
                  "voucherDate": "2026-09-08",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 88.88},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 88.88}
                  ]
                }
                """);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.debitTotal").value(88.88))
                .andExpect(jsonPath("$.creditTotal").value(88.88))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-TB-041",
                                  "voucherDate": "2026-09-09",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1.00}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
    }

    @Test
    void emptyPeriodReturnsEmptyItemsAndZeroTotals() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.debitTotal").value(0.00))
                .andExpect(jsonPath("$.creditTotal").value(0.00))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"debitTotal\":0.00")
                .contains("\"creditTotal\":0.00");
    }

    @Test
    void missingPeriodReturns404WithUnifiedErrorBody() throws Exception {
        mockMvc.perform(get("/api/periods/2099-12/trial-balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2099-12/trial-balance"));
    }

    @Test
    void disabledAccountWithHistoryStillAppears() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        postVoucher("""
                {
                  "bizKey": "BIZ-TB-050",
                  "voucherDate": "2026-09-11",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 42.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 42.00}
                  ]
                }
                """);

        Account account = accountRepository.findByCode("1001").orElseThrow();
        account.disable();
        accountRepository.saveAndFlush(account);

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].accountName").value("银行存款"))
                .andExpect(jsonPath("$.items[0].category").value("ASSET"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(42.00))
                .andExpect(jsonPath("$.items[0].creditAmount").value(0.00))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].balanceAmount").value(42.00))
                .andExpect(jsonPath("$.items[0].enabled").doesNotExist());

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-TB-051",
                                  "voucherDate": "2026-09-12",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 1.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 1.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void onlyVouchersWithinPeriodDatesAreCountedAndBoundariesInclusive() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 8));
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));

        postVoucher("""
                {
                  "bizKey": "BIZ-TB-060",
                  "voucherDate": "2026-08-31",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 10.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 10.00}
                  ]
                }
                """);
        postVoucher("""
                {
                  "bizKey": "BIZ-TB-061",
                  "voucherDate": "2026-09-01",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                  ]
                }
                """);
        postVoucher("""
                {
                  "bizKey": "BIZ-TB-062",
                  "voucherDate": "2026-09-30",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 50.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 50.00}
                  ]
                }
                """);
        postVoucher("""
                {
                  "bizKey": "BIZ-TB-063",
                  "voucherDate": "2026-10-01",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 900.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 900.00}
                  ]
                }
                """);

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(150.00))
                .andExpect(jsonPath("$.creditTotal").value(150.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].debitAmount").value(150.00));

        mockMvc.perform(get("/api/periods/2026-08/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(10.00));

        mockMvc.perform(get("/api/periods/2026-10/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(900.00));
    }

    @Test
    void reversalInAnotherPeriodOnlyCountsWithinThatPeriod() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));

        MvcResult original = postVoucher("""
                {
                  "bizKey": "BIZ-TB-070",
                  "voucherDate": "2026-09-15",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 200.00},
                    {"accountCode": "6001", "direction": "CREDIT", "amount": 200.00}
                  ]
                }
                """);
        String originalNo = JsonPath.read(original.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-TB-070", "voucherDate": "2026-10-05"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(200.00))
                .andExpect(jsonPath("$.creditTotal").value(200.00));

        mockMvc.perform(get("/api/periods/2026-10/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(0.00))
                .andExpect(jsonPath("$.items[0].creditAmount").value(200.00))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("CREDIT"))
                .andExpect(jsonPath("$.debitTotal").value(200.00));
    }

    @Test
    void existingApisStillWorkAlongsideTrialBalance() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "1001", "name": "银行存款", "category": "ASSET"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1001"));

        mockMvc.perform(get("/api/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("银行存款"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        MvcResult voucher = postVoucher("""
                {
                  "bizKey": "BIZ-TB-080",
                  "voucherDate": "2026-09-19",
                  "entries": [
                    {"accountCode": "1001", "direction": "DEBIT", "amount": 12.34},
                    {"accountCode": "1001", "direction": "CREDIT", "amount": 12.34}
                  ]
                }
                """);
        String voucherNo = JsonPath.read(voucher.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bizKey").value("BIZ-TB-080"))
                .andExpect(jsonPath("$.entries.length()").value(2));

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].debitAmount").value(12.34))
                .andExpect(jsonPath("$.items[0].creditAmount").value(12.34))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("NONE"));
    }

    private void createAccount(String code, String name, String category, boolean enabled) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "category": "%s", "enabled": %s}
                                """.formatted(code, name, category, enabled)))
                .andExpect(status().isCreated());
    }

    private MvcResult postVoucher(String body) throws Exception {
        return mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
    }
}
