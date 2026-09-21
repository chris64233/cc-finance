package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
class AccountDeactivationIntegrationTests {

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
    void deactivateUnusedAccountSucceeds() throws Exception {
        createAccount("1001", "银行存款", "ASSET");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.name").value("银行存款"))
                .andExpect(jsonPath("$.category").value("ASSET"))
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void deactivateAccountWithBalancedPostingsSucceeds() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        postVoucher("BIZ-D-001", "1001", "DEBIT", "6001", "CREDIT", "100.00");
        postVoucher("BIZ-D-002", "6001", "DEBIT", "1001", "CREDIT", "100.00");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(accountRepository.findByCode("1001")).get()
                .extracting("enabled").isEqualTo(false);
    }

    @Test
    void deactivateAccountWithNonZeroBalanceReturns409() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        postVoucher("BIZ-D-003", "1001", "DEBIT", "6001", "CREDIT", "100.00");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ACCOUNT_BALANCE_NOT_ZERO"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/accounts/1001/deactivate"));

        mockMvc.perform(get("/api/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void deactivateSucceedsAfterReversalBringsBalanceToZero() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        String voucherNo = postVoucher("BIZ-D-004", "1001", "DEBIT", "6001", "CREDIT", "100.00");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BALANCE_NOT_ZERO"));

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-D-004", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void deactivateIsIdempotentForRepeatedCalls() throws Exception {
        createAccount("1001", "银行存款", "ASSET");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void deactivateMissingAccountReturns404() throws Exception {
        mockMvc.perform(post("/api/accounts/9999/deactivate"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/accounts/9999/deactivate"));
    }

    @Test
    void deactivatedAccountRejectsPostingButHistoryAndTrialBalanceAreIntact() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        String firstVoucherNo = postVoucher("BIZ-D-005", "1001", "DEBIT", "6001", "CREDIT", "100.00");
        postVoucher("BIZ-D-006", "6001", "DEBIT", "1001", "CREDIT", "100.00");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-D-007",
                                  "voucherDate": "2026-09-21",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 50.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 50.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        assertThat(voucherRepository.count()).isEqualTo(2);

        mockMvc.perform(get("/api/vouchers/" + firstVoucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(firstVoucherNo))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"));

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debitTotal").value(200.00))
                .andExpect(jsonPath("$.creditTotal").value(200.00))
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].creditAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("NONE"));
    }

    @Test
    void deactivateAndPostingConcurrentlyStayConsistent() throws Exception {
        int rounds = 10;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                String debitCode = "8%03d".formatted(round * 2);
                String creditCode = "8%03d".formatted(round * 2 + 1);
                createAccount(debitCode, "并发借方" + round, "ASSET");
                createAccount(creditCode, "并发贷方" + round, "LIABILITY");

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger deactivateStatus = new AtomicInteger();
                AtomicInteger postingStatus = new AtomicInteger();

                Future<?> deactivateFuture = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    deactivateStatus.set(mockMvc.perform(
                            post("/api/accounts/" + debitCode + "/deactivate"))
                            .andReturn().getResponse().getStatus());
                    return null;
                });
                String bizKey = "BIZ-C-" + round;
                Future<?> postingFuture = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    postingStatus.set(mockMvc.perform(post("/api/vouchers")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "bizKey": "%s",
                                              "voucherDate": "2026-09-19",
                                              "entries": [
                                                {"accountCode": "%s", "direction": "DEBIT", "amount": 100.00},
                                                {"accountCode": "%s", "direction": "CREDIT", "amount": 100.00}
                                              ]
                                            }
                                            """.formatted(bizKey, debitCode, creditCode)))
                            .andReturn().getResponse().getStatus());
                    return null;
                });

                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                deactivateFuture.get(30, TimeUnit.SECONDS);
                postingFuture.get(30, TimeUnit.SECONDS);

                boolean deactivated = deactivateStatus.get() == 200;
                boolean posted = postingStatus.get() == 201;
                assertThat(deactivated && postingStatus.get() != 422)
                        .as("停用成功后入账必须失败 (round %d)", round)
                        .isFalse();
                assertThat(posted && deactivateStatus.get() != 409)
                        .as("入账成功后停用必须基于最新余额拒绝 (round %d)", round)
                        .isFalse();
                assertThat(deactivated || posted)
                        .as("两个操作至少有一个成功 (round %d)", round)
                        .isTrue();

                boolean enabled = accountRepository.findByCode(debitCode).orElseThrow().isEnabled();
                assertThat(enabled).isEqualTo(!deactivated);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void createAccount(String code, String name, String category) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "category": "%s"}
                                """.formatted(code, name, category)))
                .andExpect(status().isCreated());
    }

    private String postVoucher(String bizKey, String debitCode, String debitDirection,
            String creditCode, String creditDirection, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "%s",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "%s", "direction": "%s", "amount": %s},
                                    {"accountCode": "%s", "direction": "%s", "amount": %s}
                                  ]
                                }
                                """.formatted(bizKey, debitCode, debitDirection, amount,
                                creditCode, creditDirection, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.voucherNo");
    }
}
