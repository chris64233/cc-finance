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
class PeriodReopenIntegrationTests {

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
    void reopenClosedPeriodClearsClosedAtAndRecordsReopenInfo() throws Exception {
        createPeriod(2026, 9);
        closePeriod("2026-09").andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        reopenPeriod("2026-09", "发现漏记凭证，需要补录")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist())
                .andExpect(jsonPath("$.reopenedAt").isNotEmpty())
                .andExpect(jsonPath("$.reopenReason").value("发现漏记凭证，需要补录"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").doesNotExist())
                .andExpect(jsonPath("$.reopenedAt").isNotEmpty())
                .andExpect(jsonPath("$.reopenReason").value("发现漏记凭证，需要补录"));
    }

    @Test
    void reopenWithoutReasonReturns400() throws Exception {
        createPeriod(2026, 9);
        closePeriod("2026-09").andExpect(status().isOk());

        String[] invalidBodies = {
                "{}",
                "{\"reason\": null}",
                "{\"reason\": \"\"}",
                "{\"reason\": \"   \"}"
        };
        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/periods/2026-09/reopen")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.reopenedAt").doesNotExist());
    }

    @Test
    void reopenMissingPeriodReturns404() throws Exception {
        reopenPeriod("2026-01", "期间不存在")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/periods/2026-01/reopen"));
    }

    @Test
    void reopenWithLaterClosedPeriodReturns409AndKeepsPeriodUnchanged() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        closePeriod("2026-09").andExpect(status().isOk());
        closePeriod("2026-10").andExpect(status().isOk());

        MvcResult before = mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andReturn();
        String closedAt = JsonPath.read(before.getResponse().getContentAsString(), "$.closedAt");

        reopenPeriod("2026-09", "尝试跳过 10 月反关账")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PERIOD_REOPEN_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/periods/2026-09/reopen"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(closedAt))
                .andExpect(jsonPath("$.reopenedAt").doesNotExist())
                .andExpect(jsonPath("$.reopenReason").doesNotExist());

        // 更晚的已关账期间先反关账后，较早期间即可正常反关账。
        reopenPeriod("2026-10", "先反关账最新期间").andExpect(status().isOk());
        reopenPeriod("2026-09", "再反关账较早期间")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void reopenAllowedWhenLaterPeriodIsOpen() throws Exception {
        createPeriod(2026, 9);
        createPeriod(2026, 10);
        closePeriod("2026-09").andExpect(status().isOk());

        reopenPeriod("2026-09", "10 月未关账，可以反关账 9 月")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void repeatReopenOnOpenPeriodKeepsOriginalReopenInfo() throws Exception {
        createPeriod(2026, 9);
        closePeriod("2026-09").andExpect(status().isOk());
        reopenPeriod("2026-09", "首次反关账原因").andExpect(status().isOk());

        MvcResult fetched = mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andReturn();
        String reopenedAt = JsonPath.read(fetched.getResponse().getContentAsString(), "$.reopenedAt");

        reopenPeriod("2026-09", "重复反关账不应生效")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenedAt").value(reopenedAt))
                .andExpect(jsonPath("$.reopenReason").value("首次反关账原因"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reopenedAt").value(reopenedAt))
                .andExpect(jsonPath("$.reopenReason").value("首次反关账原因"));
    }

    @Test
    void voucherPostingSucceedsAfterReopen() throws Exception {
        createPeriod(2026, 9);
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");
        closePeriod("2026-09").andExpect(status().isOk());

        postVoucher("BIZ-CLOSED", "2026-09-19")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));

        reopenPeriod("2026-09", "补录漏记凭证").andExpect(status().isOk());

        postVoucher("BIZ-AFTER-REOPEN", "2026-09-19")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"));

        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void periodCanBeClosedAgainAfterReopen() throws Exception {
        createPeriod(2026, 9);
        closePeriod("2026-09").andExpect(status().isOk());
        reopenPeriod("2026-09", "反关账后再次关账").andExpect(status().isOk());

        closePeriod("2026-09")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.reopenedAt").isNotEmpty())
                .andExpect(jsonPath("$.reopenReason").value("反关账后再次关账"));

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    @Test
    void reopenAndPostConcurrentlyProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET");
        createAccount("6001", "主营业务收入", "REVENUE");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                int year = 2020 + i;
                String periodCode = "%d-06".formatted(year);
                createPeriod(year, 6);
                closePeriod(periodCode).andExpect(status().isOk());
                String bizKey = "BIZ-REOPEN-RACE-" + i;
                String voucherDate = "%d-06-15".formatted(year);
                String reopenReason = "并发反关账 " + i;

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<MvcResult> reopenFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return reopenPeriod(periodCode, reopenReason).andReturn();
                });
                Future<MvcResult> postFuture = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postVoucher(bizKey, voucherDate).andReturn();
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                MvcResult reopenResult = reopenFuture.get(30, TimeUnit.SECONDS);
                MvcResult postResult = postFuture.get(30, TimeUnit.SECONDS);

                assertThat(reopenResult.getResponse().getStatus()).isEqualTo(200);
                int postStatus = postResult.getResponse().getStatus();
                if (postStatus == 201) {
                    // 反关账先提交：入账看到 OPEN 期间，必须成功。
                    assertThat(voucherRepository.count()).isEqualTo(1);
                } else {
                    // 入账先拿到锁：期间仍处于 CLOSED，入账必须失败且不落库。
                    assertThat(postStatus).isEqualTo(409);
                    String postCode = JsonPath.read(postResult.getResponse().getContentAsString(), "$.code");
                    assertThat(postCode).isEqualTo("PERIOD_CLOSED");
                    assertThat(voucherRepository.count()).isZero();
                }

                mockMvc.perform(get("/api/periods/" + periodCode))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("OPEN"))
                        .andExpect(jsonPath("$.closedAt").doesNotExist())
                        .andExpect(jsonPath("$.reopenedAt").isNotEmpty());

                // 反关账提交后，新的入账必须成功。
                postVoucher("BIZ-REOPEN-AFTER-" + i, voucherDate)
                        .andExpect(status().isCreated());

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

    private org.springframework.test.web.servlet.ResultActions closePeriod(String periodCode) throws Exception {
        return mockMvc.perform(post("/api/periods/" + periodCode + "/close"));
    }

    private org.springframework.test.web.servlet.ResultActions reopenPeriod(String periodCode, String reason)
            throws Exception {
        return mockMvc.perform(post("/api/periods/" + periodCode + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason": "%s"}
                        """.formatted(reason)));
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
}
