package com.ccfinance;

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
    void aggregatesMultipleVouchersAndAccountsSortedByCode() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("2202", "应付账款", "LIABILITY", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        postVoucher("BIZ-TB-001", "2026-09-05", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.50}
                ]
                """);
        postVoucher("BIZ-TB-002", "2026-09-20", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 500.25},
                  {"accountCode": "2202", "direction": "DEBIT", "amount": 300.00},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 800.25}
                ]
                """);

        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.debitTotal").value(1800.75))
                .andExpect(jsonPath("$.creditTotal").value(1800.75))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.lines[0].accountName").value("银行存款"))
                .andExpect(jsonPath("$.lines[0].category").value("ASSET"))
                .andExpect(jsonPath("$.lines[0].debitAmount").value(1500.75))
                .andExpect(jsonPath("$.lines[0].creditAmount").value(0.00))
                .andExpect(jsonPath("$.lines[0].balanceDirection").value("DEBIT"))
                .andExpect(jsonPath("$.lines[0].balanceAmount").value(1500.75))
                .andExpect(jsonPath("$.lines[1].accountCode").value("2202"))
                .andExpect(jsonPath("$.lines[1].debitAmount").value(300.00))
                .andExpect(jsonPath("$.lines[1].creditAmount").value(0.00))
                .andExpect(jsonPath("$.lines[1].balanceDirection").value("DEBIT"))
                .andExpect(jsonPath("$.lines[1].balanceAmount").value(300.00))
                .andExpect(jsonPath("$.lines[2].accountCode").value("6001"))
                .andExpect(jsonPath("$.lines[2].debitAmount").value(0.00))
                .andExpect(jsonPath("$.lines[2].creditAmount").value(1800.75))
                .andExpect(jsonPath("$.lines[2].balanceDirection").value("CREDIT"))
                .andExpect(jsonPath("$.lines[2].balanceAmount").value(1800.75));
    }

    @Test
    void onlyCountsVouchersWithinPeriodDateRange() throws Exception {
        periodRepository.saveAndFlush(new AccountingPeriod(2026, 10));
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        postVoucher("BIZ-TB-010", "2026-09-30", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                ]
                """);
        postVoucher("BIZ-TB-011", "2026-10-01", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 200.00},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 200.00}
                ]
                """);

        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(100.00))
                .andExpect(jsonPath("$.creditTotal").value(100.00))
                .andExpect(jsonPath("$.lines[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.lines[0].debitAmount").value(100.00))
                .andExpect(jsonPath("$.lines[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.lines[1].creditAmount").value(100.00));

        mockMvc.perform(get("/api/reports/trial-balance/2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(200.00))
                .andExpect(jsonPath("$.creditTotal").value(200.00));
    }

    @Test
    void zeroBalanceAccountShowsDirectionNone() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("2202", "应付账款", "LIABILITY", true);

        postVoucher("BIZ-TB-020", "2026-09-10", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 400.00},
                  {"accountCode": "2202", "direction": "CREDIT", "amount": 400.00}
                ]
                """);
        postVoucher("BIZ-TB-021", "2026-09-15", """
                [
                  {"accountCode": "2202", "direction": "DEBIT", "amount": 400.00},
                  {"accountCode": "1001", "direction": "CREDIT", "amount": 400.00}
                ]
                """);

        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(800.00))
                .andExpect(jsonPath("$.creditTotal").value(800.00))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.lines[0].debitAmount").value(400.00))
                .andExpect(jsonPath("$.lines[0].creditAmount").value(400.00))
                .andExpect(jsonPath("$.lines[0].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.lines[0].balanceAmount").value(0.00))
                .andExpect(jsonPath("$.lines[1].accountCode").value("2202"))
                .andExpect(jsonPath("$.lines[1].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.lines[1].balanceAmount").value(0.00));
    }

    @Test
    void reversalVoucherKeepsAmountsOnBothSidesAndOffsetsBalance() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        String originalNo = postVoucher("BIZ-TB-030", "2026-09-05", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.50}
                ]
                """);
        mockMvc.perform(post("/api/vouchers/" + originalNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-TB-030", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(2001.00))
                .andExpect(jsonPath("$.creditTotal").value(2001.00))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.lines[0].debitAmount").value(1000.50))
                .andExpect(jsonPath("$.lines[0].creditAmount").value(1000.50))
                .andExpect(jsonPath("$.lines[0].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.lines[0].balanceAmount").value(0.00))
                .andExpect(jsonPath("$.lines[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.lines[1].debitAmount").value(1000.50))
                .andExpect(jsonPath("$.lines[1].creditAmount").value(1000.50))
                .andExpect(jsonPath("$.lines[1].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.lines[1].balanceAmount").value(0.00));
    }

    @Test
    void closedPeriodCanStillBeQueried() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        postVoucher("BIZ-TB-040", "2026-09-05", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 888.88},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 888.88}
                ]
                """);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.debitTotal").value(888.88))
                .andExpect(jsonPath("$.creditTotal").value(888.88))
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    @Test
    void periodWithoutVouchersReturnsEmptyLinesAndZeroTotals() throws Exception {
        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.debitTotal").value(0.00))
                .andExpect(jsonPath("$.creditTotal").value(0.00))
                .andExpect(jsonPath("$.lines.length()").value(0));
    }

    @Test
    void missingPeriodReturns404() throws Exception {
        mockMvc.perform(get("/api/reports/trial-balance/2026-12"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/reports/trial-balance/2026-12"));
    }

    @Test
    void disabledAccountWithHistoricalEntriesStillAppears() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        postVoucher("BIZ-TB-050", "2026-09-05", """
                [
                  {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                  {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                ]
                """);

        accountRepository.findByCode("6001").ifPresent(account -> {
            account.disable();
            accountRepository.saveAndFlush(account);
        });

        mockMvc.perform(get("/api/reports/trial-balance/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.lines[1].accountName").value("主营业务收入"))
                .andExpect(jsonPath("$.lines[1].creditAmount").value(100.00));
    }

    private void createAccount(String code, String name, String category, boolean enabled) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "category": "%s", "enabled": %s}
                                """.formatted(code, name, category, enabled)))
                .andExpect(status().isCreated());
    }

    private String postVoucher(String bizKey, String voucherDate, String entries) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "%s", "voucherDate": "%s", "entries": %s}
                                """.formatted(bizKey, voucherDate, entries)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
    }
}
