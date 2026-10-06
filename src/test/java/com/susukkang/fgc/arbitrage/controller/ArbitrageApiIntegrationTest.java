package com.susukkang.fgc.arbitrage.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 웹 서버 기동과 기존 API 응답 봉투·인가·검증 오류를 함께 확인한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ArbitrageApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(roles = "COMPLIANCE")
    void emptySearchPreservesEnvelopePaginationAndSummary() throws Exception {
        mvc.perform(get("/api/v1/arbitrage-checks").param("contractNo", "NO-SUCH-CONTRACT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.content").isEmpty())
                .andExpect(jsonPath("$.data.items.page").value(1))
                .andExpect(jsonPath("$.data.items.size").value(20))
                .andExpect(jsonPath("$.data.items.totalElements").value(0))
                .andExpect(jsonPath("$.data.summary.clearCount").value(0));
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void invalidPageKeepsBusinessErrorContract() throws Exception {
        mvc.perform(get("/api/v1/arbitrage-checks").param("page", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    @Test
    void anonymousApiIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/arbitrage-checks")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "COMPLIANCE")
    void readOnlyRoleCannotRunManualCheck() throws Exception {
        mvc.perform(post("/api/v1/contracts/1/arbitrage-check").with(csrf())
                .contentType("application/json").content("{\"asOfDate\":\"2026-07-31\",\"reason\":\"확인\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void manualCheckStillRequiresCsrfAndRequiredInput() throws Exception {
        mvc.perform(post("/api/v1/contracts/1/arbitrage-check").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/contracts/1/arbitrage-check").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }
}
