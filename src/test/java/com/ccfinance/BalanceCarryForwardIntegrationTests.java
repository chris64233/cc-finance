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
class BalanceCarryForwardIntegrationTests {

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
    void carryForwardClosedPeriodBalancesIntoNextMonthOpeningVoucher() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("3001", "本年利润", "EQUITY");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-BC-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-BC-2", "2026-09-11", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-BC-3", "2026-09-12", """
                {"accountCode": "5001", "direction": "CREDIT", "amount": 100.00},
                {"accountCode": "2001", "direction": "DEBIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        // 先结清损益，再关账源期间。
        profitLossCarryForward("2026-09", "3001").andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        MvcResult result = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.voucherDate").value("2026-10-01"))
                .andExpect(jsonPath("$.debitTotal").value(800.00))
                .andExpect(jsonPath("$.creditTotal").value(800.00))
                .andExpect(jsonPath("$.balanceCarryForwardPeriodCode").value("2026-09"))
                .andExpect(jsonPath("$.carryForwardPeriodCode").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(700.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("2001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(100.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[2].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(800.00))
                .andReturn();
        String firstVoucherNo = JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");

        // 下一期间第一天可通过试算平衡看到期初余额。
        mockMvc.perform(get("/api/periods/2026-10/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(800.00))
                .andExpect(jsonPath("$.creditTotal").value(800.00))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.category=='REVENUE' || @.category=='EXPENSE')]").isEmpty());

        // 已生成期初凭证后源期间禁止反关账。
        reopen("2026-09", "想改9月数据")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_REOPEN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/reopen"));
        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // 重复请求幂等返回同一张凭证，不重复写入。
        MvcResult repeated = carryForward("2026-09").andExpect(status().isCreated()).andReturn();
        assertThat(JsonPath.<String>read(repeated.getResponse().getContentAsString(), "$.voucherNo"))
                .isEqualTo(firstVoucherNo);
        assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
    }

    @Test
    void carryForwardAcrossYearPostsOnFirstDayOfJanuary() throws Exception {
        createPeriod(2026, 12);
        createPeriod(2027, 1);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");

        postVoucher("BIZ-YEAR-1", "2026-12-31", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 5000.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 5000.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-12/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        carryForward("2026-12")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherDate").value("2027-01-01"))
                .andExpect(jsonPath("$.balanceCarryForwardPeriodCode").value("2026-12"))
                .andExpect(jsonPath("$.debitTotal").value(5000.00))
                .andExpect(jsonPath("$.creditTotal").value(5000.00))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"));

        mockMvc.perform(get("/api/periods/2027-01/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void carryForwardMultipleAccountsKeepsDebitAndCreditDirections() throws Exception {
        createPeriod(2026, 3);
        createPeriod(2026, 4);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("1002", "应收账款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("2002", "短期借款", "LIABILITY");

        postVoucher("BIZ-DIR-1", "2026-03-05", """
                {"accountCode": "1002", "direction": "DEBIT", "amount": 400.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 400.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-DIR-2", "2026-03-06", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "2002", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-DIR-3", "2026-03-07", """
                {"accountCode": "2001", "direction": "DEBIT", "amount": 150.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 150.00}
                """).andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-03/close")).andExpect(status().isOk());

        carryForward("2026-03")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherDate").value("2026-04-01"))
                .andExpect(jsonPath("$.debitTotal").value(1250.00))
                .andExpect(jsonPath("$.creditTotal").value(1250.00))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(850.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("1002"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(400.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("2001"))
                .andExpect(jsonPath("$.entries[2].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(250.00))
                .andExpect(jsonPath("$.entries[3].accountCode").value("2002"))
                .andExpect(jsonPath("$.entries[3].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[3].amount").value(1000.00));
    }

    @Test
    void carryForwardExcludesRevenueAndExpenseAccounts() throws Exception {
        createPeriod(2026, 5);
        createPeriod(2026, 6);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "本年利润", "EQUITY");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-EX-1", "2026-05-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 2000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 2000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-EX-2", "2026-05-11", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 1200.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 1200.00}
                """).andExpect(status().isCreated());

        profitLossCarryForward("2026-05", "3001").andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-05/close")).andExpect(status().isOk());

        MvcResult result = carryForward("2026-05")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(800.00))
                .andExpect(jsonPath("$.creditTotal").value(800.00))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        List<String> accountCodes = JsonPath.parse(body).read("$.entries[*].accountCode");
        assertThat(accountCodes)
                .containsExactly("1001", "3001")
                .doesNotContain("5001", "6001");
    }

    @Test
    void carryForwardMissingSourcePeriodReturns404() throws Exception {
        carryForward("2026-01")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-01/balance-carry-forward"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void carryForwardOpenSourcePeriodReturns409() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);

        carryForward("2026-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_CLOSED"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/balance-carry-forward"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void carryForwardMissingTargetPeriodReturns409() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");
        postVoucher("BIZ-NT-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-09/close")).andExpect(status().isOk());

        carryForward("2026-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("TARGET_PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/balance-carry-forward"));

        assertThat(countBalanceCarryForwardVouchers()).isZero();
        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void carryForwardClosedTargetPeriodReturns409() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");
        postVoucher("BIZ-CT-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-09/close")).andExpect(status().isOk());
        mockMvc.perform(post("/api/periods/2026-10/close")).andExpect(status().isOk());

        carryForward("2026-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("TARGET_PERIOD_CLOSED"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/balance-carry-forward"));

        assertThat(countBalanceCarryForwardVouchers()).isZero();
        assertThat(voucherRepository.count()).isEqualTo(1);

        // 目标重新打开后可以正常结转。
        reopen("2026-10", "误关账");
        carryForward("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherDate").value("2026-10-01"));
    }

    @Test
    void carryForwardWhenAllBalancesAreZeroReturns409AndCreatesNoVoucher() throws Exception {
        createPeriod(2026, 7);
        createPeriod(2026, 8);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("3001", "实收资本", "EQUITY");

        // 期间没有任何凭证：资产负债表余额全部为零。
        mockMvc.perform(post("/api/periods/2026-07/close")).andExpect(status().isOk());
        carryForward("2026-07")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("BALANCE_ALREADY_CLEARED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-07/balance-carry-forward"));

        // 各科目借贷恰好抵消、净额为零时同样不生成空凭证。
        reopen("2026-07", "补录抵消凭证");
        postVoucher("BIZ-ZB-1", "2026-07-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 200.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 200.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-ZB-2", "2026-07-11", """
                {"accountCode": "2001", "direction": "DEBIT", "amount": 200.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 200.00}
                """).andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-07/close")).andExpect(status().isOk());

        carryForward("2026-07")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BALANCE_ALREADY_CLEARED"));

        assertThat(countBalanceCarryForwardVouchers()).isZero();
        assertThat(voucherRepository.count()).isEqualTo(2);
    }

    @Test
    void repeatedCarryForwardReturnsFirstVoucherWithoutDuplicateWrite() throws Exception {
        createPeriod(2026, 2);
        createPeriod(2026, 3);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");
        postVoucher("BIZ-IDEM-1", "2026-02-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 777.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 777.00}
                """).andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-02/close")).andExpect(status().isOk());

        MvcResult first = carryForward("2026-02").andExpect(status().isCreated()).andReturn();
        String firstVoucherNo = JsonPath.read(first.getResponse().getContentAsString(), "$.voucherNo");

        for (int i = 0; i < 3; i++) {
            MvcResult repeated = carryForward("2026-02").andExpect(status().isCreated()).andReturn();
            assertThat(JsonPath.<String>read(repeated.getResponse().getContentAsString(), "$.voucherNo"))
                    .isEqualTo(firstVoucherNo);
        }
        assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);

        // 即使目标期间随后被关账，重复请求仍返回首次生成的凭证。
        mockMvc.perform(post("/api/periods/2026-03/close")).andExpect(status().isOk());
        MvcResult afterClose = carryForward("2026-02").andExpect(status().isCreated()).andReturn();
        assertThat(JsonPath.<String>read(afterClose.getResponse().getContentAsString(), "$.voucherNo"))
                .isEqualTo(firstVoucherNo);
        assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
    }

    @Test
    void reopenIsRejectedAfterBalanceCarryForwardAndAllowedBefore() throws Exception {
        createPeriod(2026, 1);
        createPeriod(2026, 2);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");
        postVoucher("BIZ-RO-1", "2026-01-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        mockMvc.perform(post("/api/periods/2026-01/close")).andExpect(status().isOk());

        // 尚未生成期初凭证时允许反关账。
        reopen("2026-01", "先补录");
        mockMvc.perform(get("/api/periods/2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(post("/api/periods/2026-01/close")).andExpect(status().isOk());
        carryForward("2026-01").andExpect(status().isCreated());

        reopen("2026-01", "再补录")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_REOPEN_NOT_ALLOWED"));
        mockMvc.perform(get("/api/periods/2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void concurrentCarryForwardAndReopenNeverReopenSourceWithOpeningVoucher() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                final int iteration = i;
                int year = 2030 + iteration;
                String sourceCode = "%d-06".formatted(year);
                String targetCode = "%d-07".formatted(year);
                createPeriod(year, 6);
                createPeriod(year, 7);
                postVoucher("BIZ-RACE-RO-" + iteration, "%d-06-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                        {"accountCode": "3001", "direction": "CREDIT", "amount": 100.00}
                        """).andExpect(status().isCreated());
                mockMvc.perform(post("/api/periods/" + sourceCode + "/close")).andExpect(status().isOk());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> carryFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return carryForwardAndReturn(sourceCode);
                });
                Future<MvcResult> reopenFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return reopenAndReturn(sourceCode, "并发反关账");
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult carryResult = carryFuture.get(30, TimeUnit.SECONDS);
                MvcResult reopenResult = reopenFuture.get(30, TimeUnit.SECONDS);

                int carryStatus = carryResult.getResponse().getStatus();
                int reopenStatus = reopenResult.getResponse().getStatus();

                String sourceStatus = getPeriodStatus(sourceCode);
                boolean voucherExists = countBalanceCarryForwardVouchers() == 1;
                if (carryStatus == 201) {
                    // 结转先提交：源期间必须保持 CLOSED，反关账被拒绝。
                    assertThat(voucherExists).isTrue();
                    assertThat(reopenStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            reopenResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("PERIOD_REOPEN_NOT_ALLOWED");
                    assertThat(sourceStatus).isEqualTo("CLOSED");
                    assertThat(getPeriodStatus(targetCode)).isEqualTo("OPEN");
                } else {
                    // 反关账先提交：源期间为 OPEN，结转必须失败且不得留下凭证。
                    assertThat(carryStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("PERIOD_NOT_CLOSED");
                    assertThat(reopenStatus).isEqualTo(200);
                    assertThat(sourceStatus).isEqualTo("OPEN");
                    assertThat(voucherExists).isFalse();

                    // 重新关账后可以补做结转，仍然只生成一张凭证。
                    mockMvc.perform(post("/api/periods/" + sourceCode + "/close")).andExpect(status().isOk());
                    carryForward(sourceCode).andExpect(status().isCreated());
                    assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
                }

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCarryForwardAndTargetCloseNeverWriteIntoClosedTarget() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                final int iteration = i;
                int year = 2050 + iteration;
                String sourceCode = "%d-03".formatted(year);
                String targetCode = "%d-04".formatted(year);
                createPeriod(year, 3);
                createPeriod(year, 4);
                postVoucher("BIZ-RACE-TC-" + iteration, "%d-03-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 250.00},
                        {"accountCode": "3001", "direction": "CREDIT", "amount": 250.00}
                        """).andExpect(status().isCreated());
                mockMvc.perform(post("/api/periods/" + sourceCode + "/close")).andExpect(status().isOk());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> carryFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return carryForwardAndReturn(sourceCode);
                });
                Future<MvcResult> closeFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return mockMvc.perform(post("/api/periods/" + targetCode + "/close")).andReturn();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult carryResult = carryFuture.get(30, TimeUnit.SECONDS);
                MvcResult closeResult = closeFuture.get(30, TimeUnit.SECONDS);

                int carryStatus = carryResult.getResponse().getStatus();
                int closeStatus = closeResult.getResponse().getStatus();
                assertThat(closeStatus).isEqualTo(200);

                if (carryStatus == 201) {
                    // 结转先提交：目标当时仍为 OPEN，凭证有效，随后关账成功。
                    assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
                    assertThat(getPeriodStatus(targetCode)).isEqualTo("CLOSED");
                    String voucherDate = JsonPath.read(
                            carryResult.getResponse().getContentAsString(), "$.voucherDate");
                    assertThat(voucherDate).isEqualTo("%d-04-01".formatted(year));
                } else {
                    // 关账先提交：不能向 CLOSED 目标写入期初凭证。
                    assertThat(carryStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("TARGET_PERIOD_CLOSED");
                    assertThat(countBalanceCarryForwardVouchers()).isZero();

                    // 反关账目标后补做结转成功。
                    reopen(targetCode, "补做结转");
                    carryForward(sourceCode).andExpect(status().isCreated());
                    assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
                }

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDuplicateCarryForwardCreatesSingleVoucher() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                final int iteration = i;
                int year = 2060 + iteration;
                String sourceCode = "%d-05".formatted(year);
                createPeriod(year, 5);
                createPeriod(year, 6);
                postVoucher("BIZ-RACE-DUP-" + iteration, "%d-05-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 42.00},
                        {"accountCode": "3001", "direction": "CREDIT", "amount": 42.00}
                        """).andExpect(status().isCreated());
                mockMvc.perform(post("/api/periods/" + sourceCode + "/close")).andExpect(status().isOk());

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcResult>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return carryForwardAndReturn(sourceCode);
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                String voucherNo = null;
                for (Future<MvcResult> future : futures) {
                    MvcResult result = future.get(30, TimeUnit.SECONDS);
                    assertThat(result.getResponse().getStatus()).isEqualTo(201);
                    String no = JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
                    if (voucherNo == null) {
                        voucherNo = no;
                    } else {
                        assertThat(no).isEqualTo(voucherNo);
                    }
                }
                assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private String getPeriodStatus(String periodCode) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/periods/" + periodCode))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.status");
    }

    private long countBalanceCarryForwardVouchers() {
        return voucherRepository.findAll().stream()
                .filter(voucher -> voucher.getBalanceCarryForwardPeriodCode() != null)
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

    private org.springframework.test.web.servlet.ResultActions profitLossCarryForward(
            String periodCode, String equityAccountCode) throws Exception {
        return mockMvc.perform(post("/api/periods/" + periodCode + "/profit-loss-carry-forward")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"equityAccountCode": "%s"}
                        """.formatted(equityAccountCode)));
    }

    private org.springframework.test.web.servlet.ResultActions carryForward(String periodCode)
            throws Exception {
        return mockMvc.perform(post("/api/periods/" + periodCode + "/balance-carry-forward"));
    }

    private MvcResult carryForwardAndReturn(String periodCode) {
        try {
            return carryForward(periodCode).andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private org.springframework.test.web.servlet.ResultActions reopen(String periodCode, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/periods/" + periodCode + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason": "%s"}
                        """.formatted(reason)));
    }

    private MvcResult reopenAndReturn(String periodCode, String reason) {
        try {
            return reopen(periodCode, reason).andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
