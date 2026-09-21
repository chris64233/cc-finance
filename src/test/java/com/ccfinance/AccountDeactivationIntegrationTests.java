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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.ccfinance.account.AccountRepository;
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
    }

    @Test
    void deactivateZeroBalanceAccountSucceeds() throws Exception {
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
    void deactivateAccountWithNonZeroBalanceReturns409() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        postVoucher("BIZ-NZ-1", "2026-09-19", "1001", "6001")
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ACCOUNT_BALANCE_NOT_ZERO"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/accounts/1001/deactivate"));

        mockMvc.perform(get("/api/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        postVoucher("BIZ-NZ-2", "2026-09-20", "1001", "6001")
                .andExpect(status().isCreated());
    }

    @Test
    void deactivateAfterReversalZeroesBalanceAndHistoryRemains() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        MvcResult posted = postVoucher("BIZ-RA-1", "2026-09-19", "1001", "6001")
                .andExpect(status().isCreated())
                .andReturn();
        String voucherNo = JsonPath.read(posted.getResponse().getContentAsString(), "$.voucherNo");

        mockMvc.perform(post("/api/vouchers/" + voucherNo + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bizKey": "REV-RA-1", "voucherDate": "2026-09-20"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/vouchers/" + voucherNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value(voucherNo))
                .andExpect(jsonPath("$.entries[0].accountCode").value("1001"));

        mockMvc.perform(get("/api/periods/2026-09/trial-balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].accountCode").value("1001"))
                .andExpect(jsonPath("$.items[0].debitAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].creditAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].balanceDirection").value("NONE"))
                .andExpect(jsonPath("$.items[0].balanceAmount").value(0.00));
    }

    @Test
    void deactivateTwiceReturnsCurrentResultWithoutError() throws Exception {
        createAccount("1001", "银行存款", "ASSET");

        MvcResult first = mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andReturn();

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.enabled").value(false));

        String firstBody = first.getResponse().getContentAsString();
        MvcResult second = mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(second.getResponse().getContentAsString()).isEqualTo(firstBody);
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
    void postVoucherWithDeactivatedAccountReturns422AndPersistsNothing() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        mockMvc.perform(post("/api/accounts/1001/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        postVoucher("BIZ-DISABLED-1", "2026-09-19", "1001", "6001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void deactivateAndPostConcurrentlyProduceConsistentResult() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2020 + i;
                createPeriod(year, 6);
                String debitCode = "8%03d".formatted(i);
                String creditCode = "9%03d".formatted(i);
                createAccount(debitCode, "待停用资产" + i, "ASSET");
                createAccount(creditCode, "主营业务收入" + i, "REVENUE");
                String voucherDate = "%d-06-15".formatted(year);
                String bizKey = "BIZ-DEACT-RACE-" + i;

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> deactivateFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/accounts/" + debitCode + "/deactivate"))
                            .andReturn();
                });
                Future<MvcResult> postFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postVoucher(bizKey, voucherDate, debitCode, creditCode).andReturn();
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult deactivateResult = deactivateFuture.get(30, TimeUnit.SECONDS);
                MvcResult postResult = postFuture.get(30, TimeUnit.SECONDS);
                int deactivateStatus = deactivateResult.getResponse().getStatus();
                int postStatus = postResult.getResponse().getStatus();

                if (deactivateStatus == 200) {
                    assertThat(postStatus).isEqualTo(422);
                    assertThat(JsonPath.<String>read(postResult.getResponse().getContentAsString(),
                            "$.code")).isEqualTo("ACCOUNT_DISABLED");
                    assertThat(voucherRepository.count()).isZero();
                    mockMvc.perform(get("/api/accounts/" + debitCode))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.enabled").value(false));
                    postVoucher("BIZ-DEACT-AFTER-" + i, voucherDate, debitCode, creditCode)
                            .andExpect(status().isUnprocessableEntity())
                            .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
                    assertThat(voucherRepository.count()).isZero();
                } else {
                    assertThat(deactivateStatus).isEqualTo(409);
                    assertThat(JsonPath.<String>read(
                            deactivateResult.getResponse().getContentAsString(), "$.code"))
                            .isEqualTo("ACCOUNT_BALANCE_NOT_ZERO");
                    assertThat(postStatus).isEqualTo(201);
                    assertThat(voucherRepository.count()).isEqualTo(1);
                    mockMvc.perform(get("/api/accounts/" + debitCode))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.enabled").value(true));
                    mockMvc.perform(post("/api/accounts/" + debitCode + "/deactivate"))
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("ACCOUNT_BALANCE_NOT_ZERO"));
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

    private ResultActions postVoucher(String bizKey, String voucherDate,
            String debitAccount, String creditAccount) throws Exception {
        return mockMvc.perform(post("/api/vouchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "bizKey": "%s",
                          "voucherDate": "%s",
                          "entries": [
                            {"accountCode": "%s", "direction": "DEBIT", "amount": 100.00},
                            {"accountCode": "%s", "direction": "CREDIT", "amount": 100.00}
                          ]
                        }
                        """.formatted(bizKey, voucherDate, debitAccount, creditAccount)));
    }
}
