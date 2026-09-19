package com.ccfinance.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createAndQueryAccount() throws Exception {
        String body = """
                {"code": "1001", "name": "银行存款", "category": "ASSET"}
                """;
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.name").value("银行存款"))
                .andExpect(jsonPath("$.category").value("ASSET"))
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/accounts/1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("1001"))
                .andExpect(jsonPath("$.name").value("银行存款"));
    }

    @Test
    void duplicateCodeReturns409() throws Exception {
        String body = """
                {"code": "DUP-1", "name": "应收账款", "category": "ASSET"}
                """;
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ACCOUNT_CODE_CONFLICT"))
                .andExpect(jsonPath("$.path").value("/api/accounts"));
    }

    @Test
    void blankCodeOrNameReturns400() throws Exception {
        String body = """
                {"code": "   ", "name": "现金", "category": "ASSET"}
                """;
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        String blankName = """
                {"code": "1002", "name": "  ", "category": "ASSET"}
                """;
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(blankName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void queryMissingAccountReturns404() throws Exception {
        mockMvc.perform(get("/api/accounts/NO-SUCH-CODE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/accounts/NO-SUCH-CODE"));
    }
}
