package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountCategory;
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
    void closePeriodWithOnlyBalanceSheetEntriesSucceeds() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("3001", "实收资本", "EQUITY");

        postVoucher("BIZ-BS-1", "2026-09-10", "1001", "2001", 100)
                .andExpect(status().isCreated());
        postVoucher("BIZ-BS-2", "2026-09-11", "2001", "3001", 60)
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodWithUnclearedRevenueReturns409AndStaysOpen() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-REV-1", "2026-09-10", "1001", "6001", 100)
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"))
                .andExpect(jsonPath("$.message").value(containsString("6001")))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/close"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void closePeriodWithUnclearedExpenseReturns409AndStaysOpen() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6401", "管理费用", "EXPENSE");

        postVoucher("BIZ-EXP-1", "2026-09-10", "6401", "1001", 80)
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"))
                .andExpect(jsonPath("$.message").value(containsString("6401")));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void closePeriodWithMultipleProfitLossAccountsChecksEachOne() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("6002", "其他业务收入", "REVENUE");
        createAccount("6401", "管理费用", "EXPENSE");

        postVoucher("BIZ-PL-1", "2026-09-10", "1001", "6001", 100)
                .andExpect(status().isCreated());
        postVoucher("BIZ-PL-2", "2026-09-11", "1001", "6002", 80)
                .andExpect(status().isCreated());
        postVoucher("BIZ-PL-3", "2026-09-12", "6401", "1001", 50)
                .andExpect(status().isCreated());
        postVoucher("BIZ-PL-4", "2026-09-13", "1001", "6401", 50)
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"))
                .andExpect(jsonPath("$.message").value(containsString("6001")))
                .andExpect(jsonPath("$.message").value(containsString("6002")))
                .andExpect(jsonPath("$.message").value(not(containsString("6401"))));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void closePeriodAfterReversalClearsProfitLossBalance() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        MvcResult posted = postVoucher("BIZ-REV-TO-REVERSE", "2026-09-10", "1001", "6001", 100)
                .andExpect(status().isCreated())
                .andReturn();
        String voucherNo = JsonPath.read(posted.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-1", "voucherDate": "2026-09-15"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodCountsDisabledAccountsByCurrentCategory() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6401", "管理费用", "EXPENSE");

        postVoucher("BIZ-DIS-1", "2026-09-10", "6401", "1001", 100)
                .andExpect(status().isCreated());
        postVoucher("BIZ-DIS-2", "2026-10-10", "1001", "6401", 100)
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/6401/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"))
                .andExpect(jsonPath("$.message").value(containsString("6401")));

        mockMvc.perform(post("/api/periods/2026-10/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"));
    }

    @Test
    void repeatCloseReturnsCurrentResultWithoutRevalidating() throws Exception {
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        accountRepository.save(new Account("6001", "主营业务收入", AccountCategory.REVENUE, true));
        JournalVoucher voucher = new JournalVoucher("BIZ-DIRECT-1", LocalDate.of(2026, 9, 10),
                "关账后直接写入", new BigDecimal("100.00"), new BigDecimal("100.00"), "fingerprint");
        voucher.addEntry(new JournalEntry(1, "1001", Direction.DEBIT, new BigDecimal("100.00"), null));
        voucher.addEntry(new JournalEntry(2, "6001", Direction.CREDIT, new BigDecimal("100.00"), null));
        voucherRepository.save(voucher);
        voucher.assignVoucherNo();
        voucherRepository.saveAndFlush(voucher);

        MvcResult fetched = mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andReturn();
        String closedAt = JsonPath.read(fetched.getResponse().getContentAsString(), "$.closedAt");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(closedAt));
    }

    @Test
    void closeAndPostProfitLossVoucherConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2020 + i;
                String periodCode = "%d-03".formatted(year);
                createPeriod(year, 3);
                String bizKey = "BIZ-PL-RACE-" + i;
                String voucherDate = "%d-03-15".formatted(year);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> closeFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                            .andReturn();
                });
                Future<MvcResult> postFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postVoucher(bizKey, voucherDate, "1001", "6001", 100).andReturn();
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult closeResult = closeFuture.get(30, TimeUnit.SECONDS);
                MvcResult postResult = postFuture.get(30, TimeUnit.SECONDS);

                int closeStatus = closeResult.getResponse().getStatus();
                int postStatus = postResult.getResponse().getStatus();
                if (postStatus == 201) {
                    assertThat(closeStatus).isEqualTo(409);
                    String closeCode = JsonPath.read(closeResult.getResponse().getContentAsString(), "$.code");
                    assertThat(closeCode)
                            .isEqualTo("PERIOD_PROFIT_LOSS_NOT_CLEARED");
                    assertThat(voucherRepository.count()).isEqualTo(1);
                    mockMvc.perform(get("/api/periods/" + periodCode))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("OPEN"))
                            .andExpect(jsonPath("$.closedAt").doesNotExist());
                } else {
                    assertThat(postStatus).isEqualTo(409);
                    String postCode = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");
                    assertThat(postCode)
                            .isEqualTo("PERIOD_CLOSED");
                    assertThat(closeStatus).isEqualTo(200);
                    assertThat(voucherRepository.count()).isZero();
                    mockMvc.perform(get("/api/periods/" + periodCode))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("CLOSED"))
                            .andExpect(jsonPath("$.closedAt").isNotEmpty());
                }

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
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
            String debitAccount, String creditAccount, int amount) throws Exception {
        return mockMvc.perform(post("/api/vouchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "bizKey": "%s",
                          "voucherDate": "%s",
                          "entries": [
                            {"accountCode": "%s", "direction": "DEBIT", "amount": %d},
                            {"accountCode": "%s", "direction": "CREDIT", "amount": %d}
                          ]
                        }
                        """.formatted(bizKey, voucherDate, debitAccount, amount, creditAccount, amount)));
    }
}
