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
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class ProfitLossCarryForwardIntegrationTests {

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
    void carryForwardRevenueAndExpenseZeroesBalancesAndPostsToEquity() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        postVoucher("BIZ-PL-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-PL-2", "2026-09-11", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-PL-3", "2026-09-12", """
                {"accountCode": "5001", "direction": "CREDIT", "amount": 100.00},
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        MvcResult result = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.voucherDate").value("2026-09-30"))
                .andExpect(jsonPath("$.debitTotal").value(1000.00))
                .andExpect(jsonPath("$.creditTotal").value(1000.00))
                .andExpect(jsonPath("$.carryForwardPeriodCode").value("2026-09"))
                .andExpect(jsonPath("$.carryForwardEquityAccountCode").value("3001"))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].accountCode").value("5001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(200.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(1000.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[2].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(800.00))
                .andReturn();

        String firstVoucherNo = JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");

        MvcResult repeated = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andReturn();
        String repeatedVoucherNo = JsonPath.read(repeated.getResponse().getContentAsString(), "$.voucherNo");
        assertThat(repeatedVoucherNo).isEqualTo(firstVoucherNo);
        assertThat(countCarryForwardVouchers()).isEqualTo(1);

        assertProfitLossCleared("2026-09");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        carryForward("2026-09", "3001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
    }

    @Test
    void carryForwardMultipleRevenueAndExpenseAccountsBalances() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "销售费用", "EXPENSE");
        createAccount("5002", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("6051", "其他业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        postVoucher("BIZ-M-1", "2026-09-05", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-M-2", "2026-09-06", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "6051", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-M-3", "2026-09-07", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 200.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 200.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-M-4", "2026-09-08", """
                {"accountCode": "5002", "direction": "DEBIT", "amount": 900.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 900.00}
                """).andExpect(status().isCreated());

        // 收入合计 800，费用合计 1100，净亏损 300 计入权益贷方。
        carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(1100.00))
                .andExpect(jsonPath("$.creditTotal").value(1100.00))
                .andExpect(jsonPath("$.entries.length()").value(5))
                .andExpect(jsonPath("$.entries[0].accountCode").value("5001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(200.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("5002"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(900.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[2].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(500.00))
                .andExpect(jsonPath("$.entries[3].accountCode").value("6051"))
                .andExpect(jsonPath("$.entries[3].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[3].amount").value(300.00))
                .andExpect(jsonPath("$.entries[4].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[4].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[4].amount").value(300.00));

        assertProfitLossCleared("2026-09");
    }

    @Test
    void carryForwardWithZeroNetProfitOmitsZeroEquityEntry() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        postVoucher("BIZ-ZERO-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-ZERO-2", "2026-09-11", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());

        // 收入与费用余额相等，差额为零，不生成金额为零的权益分录。
        carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(500.00))
                .andExpect(jsonPath("$.creditTotal").value(500.00))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("5001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(500.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(500.00))
                .andExpect(jsonPath("$.entries[?(@.accountCode=='3001')]").isEmpty());

        assertProfitLossCleared("2026-09");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void carryForwardWithMissingEquityAccountReturns422() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        postVoucher("BIZ-NA-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "9999")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/profit-loss-carry-forward"));

        assertThat(countCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardWithDisabledEquityAccountReturns422() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");
        postVoucher("BIZ-DIS-EQ-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/3001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        carryForward("2026-09", "3001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        assertThat(countCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardWithNonEquityAccountReturns422() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        postVoucher("BIZ-NE-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        for (String nonEquityCode : List.of("1001", "2001", "5001", "6001")) {
            carryForward("2026-09", nonEquityCode)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_EQUITY"));
        }

        assertThat(countCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardWithBlankEquityAccountReturns400() throws Exception {
        createPeriod(2026, 9);

        for (String body : List.of("{}", "{\"equityAccountCode\": null}",
                "{\"equityAccountCode\": \"\"}", "{\"equityAccountCode\": \"   \"}")) {
            mockMvc.perform(post("/api/periods/2026-09/profit-loss-carry-forward")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.path")
                            .value("/api/periods/2026-09/profit-loss-carry-forward"));
        }

        assertThat(countCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardMissingPeriodReturns404() throws Exception {
        createAccount("3001", "本年利润", "EQUITY");

        carryForward("2026-01", "3001")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-01/profit-loss-carry-forward"));

        assertThat(countCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardClosedPeriodReturns409() throws Exception {
        createPeriod(2026, 9);
        createAccount("3001", "本年利润", "EQUITY");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        carryForward("2026-09", "3001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/profit-loss-carry-forward"));

        assertThat(countCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardWhenProfitLossAlreadyClearedReturns409AndCreatesNoVoucher() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");
        createAccount("3002", "本年利润", "EQUITY");

        // 期间没有任何凭证。
        carryForward("2026-09", "3002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_ALREADY_CLEARED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/profit-loss-carry-forward"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(voucherRepository.count()).isZero();

        // 只有资产/权益分录时同样视为已结清。
        postVoucher("BIZ-AC-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "3002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_ALREADY_CLEARED"));

        assertThat(countCarryForwardVouchers()).isZero();
        // 已结清失败不影响后续正常结转。
        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void carryForwardWithDifferentEquityAccountReturnsConflict() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");
        createAccount("3002", "利润分配", "EQUITY");

        postVoucher("BIZ-CF-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        MvcResult first = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.carryForwardEquityAccountCode").value("3001"))
                .andReturn();
        String firstVoucherNo = JsonPath.read(first.getResponse().getContentAsString(), "$.voucherNo");

        carryForward("2026-09", "3002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_CARRY_FORWARD_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/profit-loss-carry-forward"));

        MvcResult retrySame = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(JsonPath.<String>read(retrySame.getResponse().getContentAsString(), "$.voucherNo"))
                .isEqualTo(firstVoucherNo);
        assertThat(countCarryForwardVouchers()).isEqualTo(1);
    }

    @Test
    void carryForwardAfterPostingAllowsClose() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        postVoucher("BIZ-CLOSE-1", "2026-09-15", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-CLOSE-2", "2026-09-16", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 400.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 400.00}
                """).andExpect(status().isCreated());

        // 未结转时关账被拒绝。
        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_PROFIT_LOSS_NOT_CLEARED"));

        carryForward("2026-09", "3001")
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // 关账后普通入账被拒绝。
        postVoucher("BIZ-CLOSE-3", "2026-09-17", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
    }

    @Test
    void carryForwardAndPostingConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                final int iteration = i;
                int year = 2030 + iteration;
                String periodCode = "%d-06".formatted(year);
                createPeriod(year, 6);
                String voucherDate = "%d-06-15".formatted(year);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> carryFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return carryForwardAndReturn(periodCode, "3001");
                });
                Future<MvcResult> postFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postAndReturn("BIZ-PL-RACE-" + iteration, voucherDate, """
                            {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                            {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                            """);
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult carryResult = carryFuture.get(30, TimeUnit.SECONDS);
                MvcResult postResult = postFuture.get(30, TimeUnit.SECONDS);

                int carryStatus = carryResult.getResponse().getStatus();
                int postStatus = postResult.getResponse().getStatus();
                assertThat(postStatus).isEqualTo(201);

                if (carryStatus == 201) {
                    // 结转在入账提交后执行：必须基于包含并发入账的完整数据计算。
                    String body = carryResult.getResponse().getContentAsString();
                    assertThat(JsonPath.parse(body).read("$.entries[*].accountCode", List.class))
                            .contains("6001", "3001");
                    assertProfitLossCleared(periodCode);
                    mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                            .andExpect(status().isOk());
                } else {
                    // 结转在入账提交前执行：没有损益余额，不落空凭证。
                    assertThat(carryStatus).isEqualTo(409);
                    String carryCode = JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.code");
                    assertThat(carryCode).isEqualTo("PROFIT_LOSS_ALREADY_CLEARED");
                    assertThat(countCarryForwardVouchers()).isZero();
                    // 入账提交后再结转，必须把并发入账的 100.00 收入结平。
                    carryForward(periodCode, "3001")
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.debitTotal").value(100.00))
                            .andExpect(jsonPath("$.creditTotal").value(100.00));
                    assertProfitLossCleared(periodCode);
                    mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                            .andExpect(status().isOk());
                }

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCarryForwardWithSameEquityAccountCreatesSingleVoucher() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                final int iteration = i;
                int year = 2040 + iteration;
                String periodCode = "%d-06".formatted(year);
                createPeriod(year, 6);
                postVoucher("BIZ-PL-DUP-" + iteration, "%d-06-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                        {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                        """).andExpect(status().isCreated());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcResult>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return carryForwardAndReturn(periodCode, "3001");
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                String voucherNo = null;
                for (Future<MvcResult> future : futures) {
                    MvcResult result = future.get(30, TimeUnit.SECONDS);
                    assertThat(result.getResponse().getStatus()).isEqualTo(201);
                    String no = JsonPath.<String>read(result.getResponse().getContentAsString(), "$.voucherNo");
                    if (voucherNo == null) {
                        voucherNo = no;
                    } else {
                        assertThat(no).isEqualTo(voucherNo);
                    }
                }
                assertThat(countCarryForwardVouchers()).isEqualTo(1);
                assertProfitLossCleared(periodCode);

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertProfitLossCleared(String periodCode) throws Exception {
        mockMvc.perform(get("/api/periods/" + periodCode + "/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.category=='REVENUE' || @.category=='EXPENSE')]"
                        + ".balanceAmount").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.closeTo(0.0, 0.0001))));
    }

    private long countCarryForwardVouchers() {
        return voucherRepository.findAll().stream()
                .filter(voucher -> voucher.getCarryForwardPeriodCode() != null)
                .count();
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

    private org.springframework.test.web.servlet.ResultActions postVoucher(String bizKey,
            String voucherDate, String entries) throws Exception {
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

    private MvcResult postAndReturn(String bizKey, String voucherDate, String entries) {
        try {
            return postVoucher(bizKey, voucherDate, entries).andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private org.springframework.test.web.servlet.ResultActions carryForward(String periodCode,
            String equityAccountCode) throws Exception {
        return mockMvc.perform(post("/api/periods/" + periodCode + "/profit-loss-carry-forward")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"equityAccountCode": "%s"}
                        """.formatted(equityAccountCode)));
    }

    private MvcResult carryForwardAndReturn(String periodCode, String equityAccountCode) {
        try {
            return carryForward(periodCode, equityAccountCode).andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
