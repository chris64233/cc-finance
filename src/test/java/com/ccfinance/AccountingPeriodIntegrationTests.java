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

import com.ccfinance.account.AccountRepository;
import com.ccfinance.period.PeriodRepository;
import com.ccfinance.voucher.VoucherRepository;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class AccountingPeriodIntegrationTests {

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
    void createPeriodReturnsOpenPeriodWithGeneratedCode() throws Exception {
        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": 2026, "month": 9}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(9))
                .andExpect(jsonPath("$.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.endDate").value("2026-09-30"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void listAndGetPeriods() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);

        mockMvc.perform(get("/api/periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].periodCode").value("2026-09"))
                .andExpect(jsonPath("$[1].periodCode").value("2026-10"));

        mockMvc.perform(get("/api/periods/2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-10"))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").value("2026-10-31"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void getMissingPeriodReturns404() throws Exception {
        mockMvc.perform(get("/api/periods/2026-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-01"));
    }

    @Test
    void createDuplicatePeriodReturns409() throws Exception {
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": 2026, "month": 9}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods"));

        assertThat(periodRepository.count()).isEqualTo(1);
    }

    @Test
    void createPeriodWithInvalidYearOrMonthReturns400() throws Exception {
        String[] invalidBodies = {
                "{\"year\": 2026, \"month\": 13}",
                "{\"year\": 2026, \"month\": 0}",
                "{\"year\": 1899, \"month\": 6}",
                "{\"year\": 2101, \"month\": 6}",
                "{\"month\": 6}",
                "{\"year\": 2026}"
        };
        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/periods")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        assertThat(periodRepository.count()).isZero();
    }

    @Test
    void closeOpenPeriodRecordsClosedAt() throws Exception {
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void closePeriodTwiceKeepsOriginalClosedAt() throws Exception {
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        MvcResult fetched = mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andReturn();
        String closedAt = JsonPath.read(fetched.getResponse().getContentAsString(), "$.closedAt");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(closedAt));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").value(closedAt));
    }

    @Test
    void closeMissingPeriodReturns404() throws Exception {
        mockMvc.perform(post("/api/periods/2026-01/close"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
    }

    @Test
    void postVoucherInOpenPeriodSucceeds() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-OPEN-1", "2026-09-19")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void postVoucherWithoutPeriodReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        postVoucher("BIZ-NO-PERIOD", "2026-09-19")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void postVoucherIntoClosedPeriodReturns409AndPersistsNothing() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk());

        postVoucher("BIZ-CLOSED-1", "2026-09-19")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void closeAndPostConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2020 + i;
                String periodCode = "%d-06".formatted(year);
                createPeriod(year, 6);
                String bizKey = "BIZ-RACE-" + i;
                String voucherDate = "%d-06-15".formatted(year);

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
                    return postVoucher(bizKey, voucherDate).andReturn();
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult closeResult = closeFuture.get(30, TimeUnit.SECONDS);
                MvcResult postResult = postFuture.get(30, TimeUnit.SECONDS);

                int postStatus = postResult.getResponse().getStatus();
                int closeStatus = closeResult.getResponse().getStatus();
                if (postStatus == 201) {
                    // 入账先成功：关账必须看到包含该凭证的最新余额，
                    // 收入科目 6001 未结清，关账失败且期间保持 OPEN。
                    assertThat(closeStatus).isEqualTo(409);
                    String closeCode = JsonPath.read(closeResult.getResponse().getContentAsString(), "$.code");
                    assertThat(closeCode)
                            .isEqualTo("PERIOD_PROFIT_LOSS_NOT_CLEARED");
                    assertThat(voucherRepository.count()).isEqualTo(1);

                    mockMvc.perform(get("/api/periods/" + periodCode))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("OPEN"))
                            .andExpect(jsonPath("$.closedAt").doesNotExist());

                    // 结清损益后可以正常关账。
                    postClearingVoucher("BIZ-CLEAR-" + i, voucherDate)
                            .andExpect(status().isCreated());
                    mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status").value("CLOSED"));
                } else {
                    // 关账先成功：入账必须按现有规则失败。
                    assertThat(postStatus).isEqualTo(409);
                    String postCode = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");
                    assertThat(postCode)
                            .isEqualTo("PERIOD_CLOSED");
                    assertThat(closeStatus).isEqualTo(200);
                    assertThat(voucherRepository.count()).isZero();
                }

                mockMvc.perform(get("/api/periods/" + periodCode))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("CLOSED"));

                postVoucher("BIZ-AFTER-CLOSE-" + i, voucherDate)
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

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

    private org.springframework.test.web.servlet.ResultActions postVoucher(String bizKey, String voucherDate)
            throws Exception {
        return mockMvc.perform(post("/api/vouchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "bizKey": "%s",
                          "voucherDate": "%s",
                          "entries": [
                            {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                            {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                          ]
                        }
                        """.formatted(bizKey, voucherDate)));
    }

    private org.springframework.test.web.servlet.ResultActions postClearingVoucher(String bizKey,
            String voucherDate) throws Exception {
        return mockMvc.perform(post("/api/vouchers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "bizKey": "%s",
                          "voucherDate": "%s",
                          "entries": [
                            {"accountCode": "6001", "direction": "DEBIT", "amount": 100.00},
                            {"accountCode": "1001", "direction": "CREDIT", "amount": 100.00}
                          ]
                        }
                        """.formatted(bizKey, voucherDate)));
    }
}
