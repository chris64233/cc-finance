package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.ccfinance.account.AccountRepository;
import com.ccfinance.period.AccountingPeriod;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.JournalEntry;
import com.ccfinance.voucher.JournalVoucher;
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        voucherRepository.deleteAll();
        accountRepository.deleteAll();
        periodRepository.deleteAll();
    }

    @Test
    void carryForwardRevenueGeneratesBalancedVoucherAndAllowsClosing() throws Exception {
        setUpPeriodAndAccounts();

        postVoucher("BIZ-REV-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());

        MvcResult result = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").isNotEmpty())
                .andExpect(jsonPath("$.bizKey").value("PL-CARRY-FORWARD:2026-09"))
                .andExpect(jsonPath("$.voucherDate").value("2026-09-30"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.debitTotal").value(1000.00))
                .andExpect(jsonPath("$.creditTotal").value(1000.00))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(1000.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(1000.00))
                .andReturn();
        String voucherNo = JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(voucherNo));

        assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09"))
                .isPresent()
                .get()
                .extracting(JournalVoucher::getVoucherNo)
                .isEqualTo(voucherNo);

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(2000.00))
                .andExpect(jsonPath("$.creditTotal").value(2000.00))
                .andExpect(jsonPath("$.items[?(@.accountCode=='6001')].balanceDirection")
                        .value(contains("NONE")));

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void carryForwardMultipleRevenueAndExpenseAccountsZeroesAllBalances() throws Exception {
        setUpPeriodAndAccounts();

        postVoucher("BIZ-M-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-M-2", "2026-09-11", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "6002", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-M-3", "2026-09-12", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-M-4", "2026-09-13", """
                {"accountCode": "5002", "direction": "DEBIT", "amount": 200.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 200.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherDate").value("2026-09-30"))
                .andExpect(jsonPath("$.debitTotal").value(1500.00))
                .andExpect(jsonPath("$.creditTotal").value(1500.00))
                .andExpect(jsonPath("$.entries", hasSize(5)))
                .andExpect(jsonPath("$.entries[0].accountCode").value("5001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(300.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("5002"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(200.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[2].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(1000.00))
                .andExpect(jsonPath("$.entries[3].accountCode").value("6002"))
                .andExpect(jsonPath("$.entries[3].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[3].amount").value(500.00))
                .andExpect(jsonPath("$.entries[4].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[4].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[4].amount").value(1000.00));

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void carryForwardNetLossDebitsEquityAccount() throws Exception {
        setUpPeriodAndAccounts();

        postVoucher("BIZ-LOSS-1", "2026-09-12", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 800.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 800.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-LOSS-2", "2026-09-13", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 300.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 300.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(800.00))
                .andExpect(jsonPath("$.creditTotal").value(800.00))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].accountCode").value("5001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(800.00))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].amount").value(300.00))
                .andExpect(jsonPath("$.entries[2].accountCode").value("3001"))
                .andExpect(jsonPath("$.entries[2].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[2].amount").value(500.00));
    }

    @Test
    void carryForwardRejectsInvalidCarryAccountAndPersistsNothing() throws Exception {
        setUpPeriodAndAccounts();
        postVoucher("BIZ-INV-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        long vouchersBefore = voucherRepository.count();

        carryForward("2026-09", "9999")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.path")
                        .value("/api/periods/2026-09/profit-loss-carry-forward"));

        mockMvc.perform(post("/api/accounts/3002/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        carryForward("2026-09", "3002")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        carryForward("2026-09", "1001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_CARRY_ACCOUNT_INVALID"));

        mockMvc.perform(post("/api/periods/2026-09/profit-loss-carry-forward")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"carryAccountCode": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(voucherRepository.count()).isEqualTo(vouchersBefore);
        assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09")).isEmpty();
        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void carryForwardWithMissingPeriodReturns404() throws Exception {
        setUpPeriodAndAccounts();

        carryForward("2027-01", "3001")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path")
                        .value("/api/periods/2027-01/profit-loss-carry-forward"));
    }

    @Test
    void carryForwardOnClosedPeriodReturns409() throws Exception {
        setUpPeriodAndAccounts();

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk());

        carryForward("2026-09", "3001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

        assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09")).isEmpty();
    }

    @Test
    void carryForwardWhenProfitLossAlreadyClearedReturns409() throws Exception {
        setUpPeriodAndAccounts();

        carryForward("2026-09", "3001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_ALREADY_CLEARED"));

        postVoucher("BIZ-CLR-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-CLR-2", "2026-09-11", """
                {"accountCode": "6001", "direction": "DEBIT", "amount": 100.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "3001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_ALREADY_CLEARED"));

        assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09")).isEmpty();
        assertThat(voucherRepository.count()).isEqualTo(2);
        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void repeatCarryForwardWithSameAccountReturnsFirstVoucher() throws Exception {
        setUpPeriodAndAccounts();
        postVoucher("BIZ-RPT-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());

        MvcResult first = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andReturn();
        String firstVoucherNo = JsonPath.read(first.getResponse().getContentAsString(), "$.voucherNo");

        MvcResult second = carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andReturn();
        String secondVoucherNo = JsonPath.read(second.getResponse().getContentAsString(), "$.voucherNo");

        assertThat(secondVoucherNo).isEqualTo(firstVoucherNo);
        assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09")).isPresent();
        assertThat(voucherRepository.findAll()).hasSize(2);
    }

    @Test
    void repeatCarryForwardWithDifferentAccountReturns409() throws Exception {
        setUpPeriodAndAccounts();
        postVoucher("BIZ-CFL-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "3001")
                .andExpect(status().isCreated());

        carryForward("2026-09", "3003")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PROFIT_LOSS_CARRY_FORWARD_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path")
                        .value("/api/periods/2026-09/profit-loss-carry-forward"));

        JournalVoucher persisted = voucherRepository
                .findByProfitLossCarryPeriodCode("2026-09")
                .orElseThrow();
        assertThat(persisted.getProfitLossCarryAccountCode()).isEqualTo("3001");
        assertThat(voucherRepository.findAll()).hasSize(2);
    }

    @Test
    void carryForwardAndPostingConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("3001", "实收资本", "EQUITY");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2020 + i;
                String periodCode = "%d-07".formatted(year);
                String voucherDate = "%d-07-15".formatted(year);
                String monthEnd = "%d-07-31".formatted(year);
                createPeriod(year, 7);
                String bizKey = "BIZ-RACE-" + i;

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> carryFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return carryForward(periodCode, "3001").andReturn();
                });
                Future<MvcResult> postFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postVoucher(bizKey, voucherDate, """
                            {"accountCode": "1001", "direction": "DEBIT", "amount": 250.00},
                            {"accountCode": "6001", "direction": "CREDIT", "amount": 250.00}
                            """).andReturn();
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult carryResult = carryFuture.get(30, TimeUnit.SECONDS);
                MvcResult postResult = postFuture.get(30, TimeUnit.SECONDS);

                int carryStatus = carryResult.getResponse().getStatus();
                int postStatus = postResult.getResponse().getStatus();
                assertThat(postStatus).isEqualTo(201);
                if (carryStatus == 201) {
                    // 结转先拿到期间锁：结转凭证必须包含并发提交的入账，
                    // 承接权益金额为 250.00，随后可以正常关账。
                    String carryBody = carryResult.getResponse().getContentAsString();
                    assertThat((String) JsonPath.read(carryBody, "$.voucherDate"))
                            .isEqualTo(monthEnd);
                    List<?> equityAmounts = JsonPath.read(carryBody,
                            "$.entries[?(@.accountCode=='3001')].amount");
                    assertThat(equityAmounts).hasSize(1);
                    assertThat(new BigDecimal(equityAmounts.get(0).toString()))
                            .isEqualByComparingTo("250.00");

                    JournalVoucher carryVoucher = voucherRepository
                            .findByProfitLossCarryPeriodCode(periodCode).orElseThrow();
                    assertThat(carryVoucher.getEntries())
                            .anySatisfy(entry -> {
                                assertThat(entry.getAccountCode()).isEqualTo("3001");
                                assertThat(entry.getAmount().doubleValue()).isEqualTo(250.00);
                            });
                    assertThat(voucherRepository.findAll()).hasSize(2);

                    mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("CLOSED"));
                } else {
                    // 入账先拿到期间锁并提交：结转在空期间上完成校验，
                    // 识别为损益已结清，返回 409 且不生成凭证；期间保持 OPEN。
                    assertThat(carryStatus).isEqualTo(409);
                    String carryCode = JsonPath.read(
                            carryResult.getResponse().getContentAsString(), "$.code");
                    assertThat(carryCode).isEqualTo("PROFIT_LOSS_ALREADY_CLEARED");
                    assertThat(voucherRepository.findByProfitLossCarryPeriodCode(periodCode))
                            .isEmpty();
                    assertThat(voucherRepository.findAll()).hasSize(1);

                    mockMvc.perform(get("/api/periods/" + periodCode))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("OPEN"));

                    // 基于锁定后的完整数据重新结转，必须看到并发入账的 250.00。
                    MvcResult retried = carryForward(periodCode, "3001")
                            .andExpect(status().isCreated())
                            .andReturn();
                    List<?> retriedAmounts = JsonPath.read(
                            retried.getResponse().getContentAsString(),
                            "$.entries[?(@.accountCode=='3001')].amount");
                    assertThat(retriedAmounts).hasSize(1);
                    assertThat(new BigDecimal(retriedAmounts.get(0).toString()))
                            .isEqualByComparingTo("250.00");

                    mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("CLOSED"));
                }

                postVoucher("BIZ-AFTER-" + i, voucherDate, """
                        {"accountCode": "1001", "direction": "DEBIT", "amount": 10.00},
                        {"accountCode": "6001", "direction": "CREDIT", "amount": 10.00}
                        """)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

                voucherRepository.deleteAll();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCarryForwardWithSameAccountCreatesOnlyOneVoucher() throws Exception {
        setUpPeriodAndAccounts();
        postVoucher("BIZ-CC-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 120.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 120.00}
                """).andExpect(status().isCreated());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<MvcResult> results = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    results.add(carryForward("2026-09", "3001").andReturn());
                    return null;
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(results).hasSize(2);
            for (MvcResult result : results) {
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
            }
            String firstNo = JsonPath.read(results.get(0).getResponse().getContentAsString(),
                    "$.voucherNo");
            String secondNo = JsonPath.read(results.get(1).getResponse().getContentAsString(),
                    "$.voucherNo");
            assertThat(firstNo).isEqualTo(secondNo);
            assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09")).isPresent();
            assertThat(voucherRepository.findAll()).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCarryForwardWithDifferentAccountsNeverPersistsSecondVoucher() throws Exception {
        setUpPeriodAndAccounts();
        postVoucher("BIZ-CD-1", "2026-09-19", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 120.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 120.00}
                """).andExpect(status().isCreated());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<MvcResult> firstFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return carryForward("2026-09", "3001").andReturn();
            });
            Future<MvcResult> secondFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return carryForward("2026-09", "3003").andReturn();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                    firstFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus(),
                    secondFuture.get(30, TimeUnit.SECONDS).getResponse().getStatus());

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09")).isPresent();
            assertThat(voucherRepository.findAll()).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void databaseRejectsTwoCarryForwardVouchersForSamePeriod() {
        AccountingPeriod period = new AccountingPeriod(2026, 9);
        periodRepository.saveAndFlush(period);

        transactionTemplate.execute(status -> {
            JournalVoucher first = new JournalVoucher("PL-CARRY-FORWARD:2026-09",
                    LocalDate.of(2026, 9, 30), "损益结转 2026-09",
                    new BigDecimal("10.00"), new BigDecimal("10.00"), "fp1");
            first.addEntry(new JournalEntry(1, "6001",
                    Direction.DEBIT, new BigDecimal("10.00"), null));
            first.addEntry(new JournalEntry(2, "3001",
                    Direction.CREDIT, new BigDecimal("10.00"), null));
            first.markAsProfitLossCarryForward("2026-09", "3001");
            voucherRepository.save(first);
            first.assignVoucherNo();
            voucherRepository.saveAndFlush(first);
            return null;
        });

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
                    JournalVoucher second = new JournalVoucher("PL-CARRY-FORWARD-DUP:2026-09",
                            LocalDate.of(2026, 9, 30), "重复损益结转 2026-09",
                            new BigDecimal("10.00"), new BigDecimal("10.00"), "fp2");
                    second.addEntry(new JournalEntry(1, "6001",
                            Direction.DEBIT, new BigDecimal("10.00"), null));
                    second.addEntry(new JournalEntry(2, "3003",
                            Direction.CREDIT, new BigDecimal("10.00"), null));
                    second.markAsProfitLossCarryForward("2026-09", "3003");
                    voucherRepository.save(second);
                    second.assignVoucherNo();
                    voucherRepository.saveAndFlush(second);
                    return null;
                }))
                .rootCause()
                .isInstanceOf(org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException.class);

        assertThat(voucherRepository.findByProfitLossCarryPeriodCode("2026-09"))
                .isPresent()
                .get()
                .extracting(JournalVoucher::getProfitLossCarryAccountCode)
                .isEqualTo("3001");
    }

    @Test
    void carryForwardWithEqualRevenueAndExpenseSkipsZeroEquityEntry() throws Exception {
        setUpPeriodAndAccounts();

        postVoucher("BIZ-EQ-1", "2026-09-10", """
                {"accountCode": "1001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "6001", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());
        postVoucher("BIZ-EQ-2", "2026-09-11", """
                {"accountCode": "5001", "direction": "DEBIT", "amount": 500.00},
                {"accountCode": "1001", "direction": "CREDIT", "amount": 500.00}
                """).andExpect(status().isCreated());

        carryForward("2026-09", "3001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitTotal").value(500.00))
                .andExpect(jsonPath("$.creditTotal").value(500.00))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[?(@.accountCode=='3001')]").isEmpty())
                .andExpect(jsonPath("$.entries[0].accountCode").value("5001"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].accountCode").value("6001"))
                .andExpect(jsonPath("$.entries[1].direction").value("DEBIT"));

       mockMvc.perform(post("/api/periods/2026-09/close"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CLOSED"));
   }

    private void setUpPeriodAndAccounts() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("5001", "管理费用", "EXPENSE");
        createAccount("5002", "销售费用", "EXPENSE");
        createAccount("6001", "主营业务收入", "REVENUE");
        createAccount("6002", "其他业务收入", "REVENUE");
        createAccount("3001", "实收资本", "EQUITY");
        createAccount("3002", "资本公积", "EQUITY");
        createAccount("3003", "盈余公积", "EQUITY");
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

    private org.springframework.test.web.servlet.ResultActions carryForward(String periodCode,
            String carryAccountCode) throws Exception {
        return mockMvc.perform(
                        post("/api/periods/" + periodCode + "/profit-loss-carry-forward")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"carryAccountCode": "%s"}
                                        """.formatted(carryAccountCode)));
    }
}
