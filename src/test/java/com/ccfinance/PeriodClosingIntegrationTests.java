package com.ccfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import com.ccfinance.common.ApiException;
import com.ccfinance.period.AccountingPeriodRepository;
import com.ccfinance.period.PeriodService;
import com.ccfinance.period.PeriodStatus;
import com.ccfinance.voucher.Direction;
import com.ccfinance.voucher.VoucherRepository;
import com.ccfinance.voucher.VoucherService;
import com.ccfinance.voucher.dto.EntryRequest;
import com.ccfinance.voucher.dto.VoucherRequest;

@SpringBootTest
@AutoConfigureMockMvc
class PeriodClosingIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private AccountingPeriodRepository periodRepository;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private VoucherService voucherService;

    @BeforeEach
    void cleanDatabase() {
        voucherRepository.deleteAll();
        accountRepository.deleteAll();
        periodRepository.deleteAll();
    }

    @Test
    void createPeriodAndQueryByCodeAndList() throws Exception {
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

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        mockMvc.perform(get("/api/periods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].periodCode").value("2026-09"));
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
        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": 2026, "month": 13}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": 2026, "month": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": 1800, "month": 6}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": 2026}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(periodRepository.count()).isZero();
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
    void closeOpenPeriodSucceeds() throws Exception {
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-09"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        mockMvc.perform(get("/api/periods/2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void closeMissingPeriodReturns404() throws Exception {
        mockMvc.perform(post("/api/periods/2026-01/close"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"));
    }

    @Test
    void closePeriodTwiceKeepsOriginalClosedAt() throws Exception {
        createPeriod(2026, 9);

        MvcResult first = mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andReturn();
        String firstClosedAt = com.jayway.jsonpath.JsonPath
                .read(first.getResponse().getContentAsString(), "$.closedAt");

        Thread.sleep(50);

        MvcResult second = mockMvc.perform(post("/api/periods/2026-09/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").value(firstClosedAt))
                .andReturn();
        String secondClosedAt = com.jayway.jsonpath.JsonPath
                .read(second.getResponse().getContentAsString(), "$.closedAt");

        assertThat(secondClosedAt).isEqualTo(firstClosedAt);
    }

    @Test
    void postVoucherInOpenPeriodSucceeds() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createPeriod(2026, 9);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-P-001",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.voucherNo").isNotEmpty());

        assertThat(voucherRepository.count()).isEqualTo(1);
    }

    @Test
    void postVoucherWithoutPeriodReturns422AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-P-002",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERIOD_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void postVoucherInClosedPeriodReturns409AndPersistsNothing() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createPeriod(2026, 9);
        closePeriod("2026-09");

        mockMvc.perform(post("/api/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bizKey": "BIZ-P-003",
                                  "voucherDate": "2026-09-19",
                                  "entries": [
                                    {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00},
                                    {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
                                  ]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"))
                .andExpect(jsonPath("$.path").value("/api/vouchers"));

        assertThat(voucherRepository.count()).isZero();
    }

    @Test
    void concurrentCloseAndPostingProduceConsistentResult() throws Exception {
        createAccount("1001", "银行存款", "ASSET", true);
        createAccount("6001", "主营业务收入", "REVENUE", true);
        createPeriod(2026, 9);

        VoucherRequest request = new VoucherRequest("BIZ-P-CONCURRENT",
                LocalDate.of(2026, 9, 19), "并发测试",
                List.of(
                        new EntryRequest("1001", Direction.DEBIT, new BigDecimal("100.00"), null),
                        new EntryRequest("6001", Direction.CREDIT, new BigDecimal("100.00"), null)));

        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<PeriodStatus> closeFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return periodService.close("2026-09").status();
            });
            Future<Integer> postFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    voucherService.post(request);
                    return 201;
                } catch (ApiException ex) {
                    return ex.getStatus().value();
                }
            });

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            PeriodStatus closeStatus = closeFuture.get(10, TimeUnit.SECONDS);
            int postStatus = postFuture.get(10, TimeUnit.SECONDS);

            assertThat(closeStatus).isEqualTo(PeriodStatus.CLOSED);
            if (postStatus == 201) {
                assertThat(voucherRepository.count()).isEqualTo(1);
            } else {
                assertThat(postStatus).isEqualTo(409);
                assertThat(voucherRepository.count()).isZero();
            }
        } finally {
            executor.shutdownNow();
        }

        VoucherRequest afterClose = new VoucherRequest("BIZ-P-AFTER-CLOSE",
                LocalDate.of(2026, 9, 20), "关账后入账",
                List.of(
                        new EntryRequest("1001", Direction.DEBIT, new BigDecimal("50.00"), null),
                        new EntryRequest("6001", Direction.CREDIT, new BigDecimal("50.00"), null)));
        try {
            voucherService.post(afterClose);
            assertThat(false).as("关账后入账必须失败").isTrue();
        } catch (ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
            assertThat(ex.getCode()).isEqualTo("PERIOD_CLOSED");
        }
    }

    private void createAccount(String code, String name, String category, boolean enabled) throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "category": "%s", "enabled": %s}
                                """.formatted(code, name, category, enabled)))
                .andExpect(status().isCreated());
    }

    private void createPeriod(int year, int month) throws Exception {
        mockMvc.perform(post("/api/periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"year": %d, "month": %d}
                                """.formatted(year, month)))
                .andExpect(status().isCreated());
    }

    private void closePeriod(String periodCode) throws Exception {
        mockMvc.perform(post("/api/periods/" + periodCode + "/close"))
                .andExpect(status().isOk());
    }
}
