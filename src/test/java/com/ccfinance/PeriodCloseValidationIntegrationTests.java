package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ccfinance.account.AccountRepository;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.JournalEntry;
import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class PeriodCloseValidationIntegrationTests {

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
    }

    @Test
    void closePeriodWithoutVouchersSucceeds() throws Exception {
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodWithOnlyAssetLiabilityEquityEntriesSucceeds() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("3001", "实收资本", "EQUITY");

        postVoucher("BIZ-AL-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-AL-2", "2026-09-11", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodWithUnclearedRevenueReturns409AndKeepsPeriodOpen() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-REV-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/close"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void closePeriodWithUnclearedExpenseReturns409AndKeepsPeriodOpen() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");

        postVoucher("BIZ-EXP-1", "2026-09-19", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 80.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 80.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void closePeriodWithMultipleProfitLossAccountsRequiresAllCleared() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-MULTI-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-MULTI-2", "2026-09-11", """
                {"accountCode": "6001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-MULTI-3", "2026-09-12", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 40.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 40.00}
                """).andExpect(status().isCreated());

        // 6001 已结清，但 5001 未结清，仍不能关账。
        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"));

        postVoucher("BIZ-MULTI-4", "2026-09-13", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 40.00},
                {"accountCode": "5001", "direction": "CREDIT", "amount": 40.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodAfterReversalZeroesProfitLossBalanceSucceeds() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        MvcResult posted = postVoucher("BIZ-REV-TO-REVERSE", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated()).andReturn();
        String voucherNo = JsonPath.read(posted.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-REVERSE-1", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodValidatesDisabledAccountsByCurrentCategory() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-DIS-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-DIS-2", "2026-10-05", """
                {"accountCode": "6001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        // 6001 累计借贷相等，可以停用。
        mockMvc.perform(post("/api/accounts/6001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // 已停用科目仍按当前类别参与校验：9 月内 6001 贷方 100 未结清。
        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void closePeriodIgnoresVouchersFromOtherPeriods() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-OTHER-PERIOD", "2026-10-05", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void repeatCloseOnClosedPeriodSkipsRevalidation() throws Exception {
        createPeriod(2026, 9);
        createAccount("6001", "主营业务收入", "REVENUE");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        MvcResult fetched = mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andReturn();
        String closedAt = JsonPath.read(fetched.getResponse().getContentAsString(), "$.closedAt");

        // 直接写入一张未结清损益的凭证（绕过入账规则），重复关账不应重新校验。
        JournalVoucher voucher = new JournalVoucher("BIZ-DIRECT-1",
                java.time.LocalDate.of(2026, 9, 15), "直接写入",
                new BigDecimal("100.00"), new BigDecimal("100.00"), "fingerprint");
        voucher.addEntry(new JournalEntry(1, "6001", Direction.CREDIT,
                new BigDecimal("100.00"), null));
        voucher.addEntry(new JournalEntry(2, "1001", Direction.DEBIT,
                new BigDecimal("100.00"), null));
        voucherRepository.save(voucher);
        voucher.assignVoucherNo();
        voucherRepository.saveAndFlush(voucher);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(closedAt));
    }

    private void createPeriod(int year, int month) throws Exception {
        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": %d, "month": %d}
                                """.formatted(year, month)))
                .andExpect(status().isCreated());
    }

    private void createAccount(String code, String name, String category) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "category": "%s"}
                                """.formatted(code, name, category)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions postVoucher(String bizKey, String voucherDate,
            String entries) throws Exception {
        return mockMvc.perform(post("/api/vouchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "bizKey": "%s",
                          "voucherDate": "%s",
                          "entries": [
                            %s
                          ]
                        }
                        """.formatted(bizKey, voucherDate, entries)));
    }
}
