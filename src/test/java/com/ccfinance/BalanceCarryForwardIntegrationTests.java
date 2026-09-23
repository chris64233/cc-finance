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
    void carryForwardBalancesToNextMonthOpeningVoucher() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");

        postVoucher("BIZ-BAL-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());

        closePeriod("2026-09");

        MvcResult result = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.voucherDate").value("2026-10-01"))
                .andExpect(jsonPath("$.debitTotal").value(1000.00))
                .andExpect(jsonPath("$.creditTotal").value(1000.00))
                .andExpect(jsonPath("$.balanceCarryForwardPeriodCode").value("2026-09"))
                .andExpect(jsonPath("$.carryForwardPeriodCode").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1000.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("2001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(1000.00))
                .andReturn();

        String voucherNo = JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherDate").value("2026-10-01"))
                .andExpect(jsonPath("$.balanceCarryForwardPeriodCode").value("2026-09"));
    }

    @Test
    void carryForwardAcrossYearUsesNextJanuaryFirst() throws Exception {
        createPeriod(2026, 12);
        createPeriod(2027, 1);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("3001", "实收资本", "EQUITY");

        postVoucher("BIZ-YEAR-1", "2026-12-31", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 5000.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 5000.00}
                """).andExpect(status().isCreated());

        closePeriod("2026-12");

        carryForward("2026-12")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherDate").value("2027-01-01"))
                .andExpect(jsonPath("$.debitTotal").value(5000.00))
                .andExpect(jsonPath("$.creditTotal").value(5000.00))
                .andExpect(jsonPath("$.balanceCarryForwardPeriodCode").value("2026-12"))
                .andExpect(jsonPath("$.entries.length()").value(2));
    }

    @Test
    void carryForwardIncludesMultipleBalanceSheetAccountsAndBothDirections() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "库存现金", "ASSET");
        createAccount("1002", "银行存款", "ASSET");
        createAccount("2001", "应付账款", "LIABILITY");
        createAccount("3001", "实收资本", "EQUITY");

        postVoucher("BIZ-MM-1", "2026-09-02", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-MM-2", "2026-09-03", """
                {"accountCode": "1002", "direction": "DEBIT", "amount": 700.00},
                {"accountCode": "3001", "direction": "CREDIT", "amount": 700.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-MM-3", "2026-09-04", """
                {"accountCode": "2001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        closePeriod("2026-09");

        carryForward("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(900.00))
                .andExpect(jsonPath("$.creditTotal").value(900.00))
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(200.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("1002"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(700.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("2001"))
                .andExpect(jsonPath("$.entries[2].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(200.00))
                .andExpect(jsonPath("$.entries[3].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[3].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[3].amount").value(700.00));
    }

    @Test
    void carryForwardExcludesRevenueAndExpenseAccounts() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "本年利润", "EQUITY");

        postVoucher("BIZ-EX-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-EX-2", "2026-09-11", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 400.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 400.00}
                """).andExpect(status().isCreated());

        // 真实工作流：先损益结转到权益使收入费用归零，再关账。
        mockMvc.perform(post("/api/periods/2026-09/profit-loss-carry-forward")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"equityAccountCode": "3001"}
                                """))
                .andExpect(status().isCreated());
        closePeriod("2026-09");

        MvcResult result = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(600.00))
                .andExpect(jsonPath("$.creditTotal").value(600.00))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(600.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(600.00))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.parse(body).read("$.entries[*].accountCode", List.class))
                .doesNotContain("5001", "6001");
    }

    @Test
    void carryForwardMissingSourcePeriodReturns404() throws Exception {
        carryForward("2026-01")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
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

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(countBalanceCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardMissingTargetPeriodReturns422AndCreatesNoVoucher() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");
        postVoucher("BIZ-NT-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        closePeriod("2026-09");

        carryForward("2026-09")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/balance-carry-forward"));

        assertThat(countBalanceCarryForwardVouchers()).isZero();
    }

    @Test
    void carryForwardClosedTargetPeriodReturns409AndCreatesNoVoucher() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");
        postVoucher("BIZ-CT-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        closePeriod("2026-09");
        closePeriod("2026-10");

        carryForward("2026-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/balance-carry-forward"));

        assertThat(countBalanceCarryForwardVouchers()).isZero();

        reopenPeriod("2026-10", "录入期初");
        carryForward("2026-09")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherDate").value("2026-10-01"));
    }

    @Test
    void carryForwardWhenAllBalancesZeroReturns409AndCreatesNoVoucher() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);

        // 期间没有任何凭证。
        closePeriod("2026-09");
        carryForward("2026-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("BALANCE_ALREADY_CLEARED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/balance-carry-forward"));

        assertThat(countBalanceCarryForwardVouchers()).isZero();

        // 借贷自相平衡的资产科目（净额为零）同样视为全部清零。
        createAccount("1001", "银行存款", "ASSET");
        reopenPeriod("2026-09", "补录");
        postVoucher("BIZ-ZERO-BAL-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        closePeriod("2026-09");

        carryForward("2026-09")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BALANCE_ALREADY_CLEARED"));
        assertThat(countBalanceCarryForwardVouchers()).isZero();

        // 全部清零不影响期间状态，也不影响后续正常结转。
        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void repeatedCarryForwardReturnsFirstVoucherWithoutDuplicateWrite() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");
        postVoucher("BIZ-RP-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 800.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 800.00}
                """).andExpect(status().isCreated());
        closePeriod("2026-09");

        MvcResult first = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andReturn();
        String firstVoucherNo = JsonPath.read(first.getResponse().getContentAsString(), "$.voucherNo");

        MvcResult repeated = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(JsonPath.<String>read(repeated.getResponse().getContentAsString(), "$.voucherNo"))
                .isEqualTo(firstVoucherNo);

        MvcResult third = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(JsonPath.<String>read(third.getResponse().getContentAsString(), "$.voucherNo"))
                .isEqualTo(firstVoucherNo);

        assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
        assertThat(voucherRepository.findAll()).hasSize(2);
    }

    @Test
    void sourcePeriodCannotReopenAfterCarryForward() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");
        postVoucher("BIZ-RO-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 200.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 200.00}
                """).andExpect(status().isCreated());
        closePeriod("2026-09");
        carryForward("2026-09").andExpect(status().isCreated());

        mockMvc.perform(post("/api/periods/2026-09/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "想反关账9月"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_REOPEN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/reopen"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // 反关账被拒绝后，期初凭证仍然只有一张且可查。
        assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);

        // 不影响目标期间正常关账。
        closePeriod("2026-10");
    }

    @Test
    void carryForwardOnlyConsidersVouchersUpToSourcePeriodEnd() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");

        postVoucher("BIZ-CUT-1", "2026-09-30", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        closePeriod("2026-09");
        carryForward("2026-09").andExpect(status().isCreated());

        // 目标期间内的新业务发生额不能改变已生成的期初凭证。
        postVoucher("BIZ-CUT-2", "2026-10-05", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 900.00},
                {"accountCode": "2001", "direction": "CREDIT", "amount": 900.00}
                """).andExpect(status().isCreated());

        MvcResult repeated = carryForward("2026-09")
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(JsonPath.<Integer>read(repeated.getResponse().getContentAsString(),
                "$.entries.length()")).isEqualTo(2);
    }

    @Test
    void carryForwardAndReopenSourceConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2030 + i;
                String sourcePeriodCode = "%d-06".formatted(year);
                String targetPeriodCode = "%d-07".formatted(year);
                createPeriod(year, 6);
                createPeriod(year, 7);
                postVoucher("BIZ-BCF-REOPEN-" + i, "%d-06-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                        {"accountCode": "2001", "direction": "CREDIT", "amount": 100.00}
                        """).andExpect(status().isCreated());
                closePeriod(sourcePeriodCode);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> carryFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return carryForwardAndReturn(sourcePeriodCode);
                });
                Future<MvcResult> reopenFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return reopenAndReturn(sourcePeriodCode, "并发反关账源期间");
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult carryResult = carryFuture.get(30, TimeUnit.SECONDS);
                MvcResult reopenResult = reopenFuture.get(30, TimeUnit.SECONDS);

                int carryStatus = carryResult.getResponse().getStatus();
                int reopenStatus = reopenResult.getResponse().getStatus();

                if (carryStatus == 201) {
                    // 结转先提交：源期间必须保持 CLOSED，反关账被拒绝。
                    assertThat(reopenStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            reopenResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("PERIOD_REOPEN_NOT_ALLOWED");
                    mockMvc.perform(get("/api/periods/" + sourcePeriodCode))
                            .andExpect(jsonPath("$.status").value("CLOSED"));
                    assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.voucherDate"))
                            .isEqualTo(targetPeriodCode + "-01");
                } else {
                    // 反关账先提交：结转必须看到 OPEN 状态并拒绝，且不能落下凭证。
                    assertThat(carryStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("PERIOD_NOT_CLOSED");
                    assertThat(reopenStatus).isEqualTo(200);
                    mockMvc.perform(get("/api/periods/" + sourcePeriodCode))
                            .andExpect(jsonPath("$.status").value("OPEN"));
                    assertThat(countBalanceCarryForwardVouchers()).isZero();

                    // 重新关账后再次结转必须成功，证明失败事务没有留下任何脏数据。
                    closePeriod(sourcePeriodCode);
                    carryForward(sourcePeriodCode)
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.voucherDate")
                                    .value(targetPeriodCode + "-01"));
                }

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void carryForwardAndCloseTargetConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2050 + i;
                String sourcePeriodCode = "%d-06".formatted(year);
                String targetPeriodCode = "%d-07".formatted(year);
                createPeriod(year, 6);
                createPeriod(year, 7);
                postVoucher("BIZ-BCF-CLOSE-" + i, "%d-06-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                        {"accountCode": "2001", "direction": "CREDIT", "amount": 100.00}
                        """).andExpect(status().isCreated());
                closePeriod(sourcePeriodCode);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> carryFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return carryForwardAndReturn(sourcePeriodCode);
                });
                Future<MvcResult> closeFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return closeAndReturn(targetPeriodCode);
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult carryResult = carryFuture.get(30, TimeUnit.SECONDS);
                MvcResult closeResult = closeFuture.get(30, TimeUnit.SECONDS);

                int carryStatus = carryResult.getResponse().getStatus();
                int closeStatus = closeResult.getResponse().getStatus();
                assertThat(closeStatus).isEqualTo(200);

                mockMvc.perform(get("/api/periods/" + targetPeriodCode))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CLOSED"));

                if (carryStatus == 201) {
                    // 结转先提交：期初凭证落在目标期间第一天，之后目标期间正常关账。
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.voucherDate"))
                            .isEqualTo(targetPeriodCode + "-01");
                    assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(),
                            "$.balanceCarryForwardPeriodCode"))
                            .isEqualTo(sourcePeriodCode);
                } else {
                    // 关账先提交：结转必须看到 CLOSED 状态并拒绝，目标期间关账后不能再写入。
                    assertThat(carryStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            carryResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("PERIOD_CLOSED");
                    assertThat(countBalanceCarryForwardVouchers()).isZero();

                    // 目标期间反关账后结转成功，证明失败事务没有留下凭证或分录。
                    reopenPeriod(targetPeriodCode, "录入期初");
                    carryForward(sourcePeriodCode)
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.voucherDate")
                                    .value(targetPeriodCode + "-01"));
                    assertThat(countBalanceCarryForwardVouchers()).isEqualTo(1);
                }

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCarryForwardCreatesSingleVoucher() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("2001", "短期借款", "LIABILITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 8; i++) {
                int year = 2070 + i;
                String sourcePeriodCode = "%d-06".formatted(year);
                createPeriod(year, 6);
                createPeriod(year, 7);
                postVoucher("BIZ-BCF-DUP-" + i, "%d-06-15".formatted(year), """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                        {"accountCode": "2001", "direction": "CREDIT", "amount": 100.00}
                        """).andExpect(status().isCreated());
                closePeriod(sourcePeriodCode);

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcResult>> futures = new java.util.ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return carryForwardAndReturn(sourcePeriodCode);
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

    private void closePeriod(String periodCode) throws Exception {
        mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    private MvcResult closeAndReturn(String periodCode) {
        try {
            return mockMvc.perform(post("/api/periods/" + periodCode + "/close")).andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void reopenPeriod(String periodCode, String reason) throws Exception {
        mockMvc.perform(post("/api/periods/" + periodCode + "/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "%s"}
                                """.formatted(reason)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    private MvcResult reopenAndReturn(String periodCode, String reason) {
        try {
            return mockMvc.perform(post("/api/periods/" + periodCode + "/reopen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason": "%s"}
                                    """.formatted(reason)))
                    .andReturn();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
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

}
