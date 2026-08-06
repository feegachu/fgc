package com.susukkang.fgc.base.controller;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.service.CommissionItemService;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionItemController.class)
@Import({CommissionItemController.class, GlobalExceptionHandler.class})
class CommissionItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommissionItemService commissionItemService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    @Test
    void returnsEffectiveCommissionItemsForSettlementRole() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 8, 4);
        given(commissionItemService.findEffectiveItems(asOf)).willReturn(List.of(
                new CommissionItemResponse(
                        "BASE_COMMISSION", "FC 기본수수료", "PAYMENT", "SALES",
                        LocalDate.of(2026, 1, 1), null
                )
        ));

        mockMvc.perform(get("/api/base/commission-items")
                        .param("asOf", "2026-08-04")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].itemCode").value("BASE_COMMISSION"))
                .andExpect(jsonPath("$.data[0].itemName").value("FC 기본수수료"))
                .andExpect(jsonPath("$.data[0].cashflowType").value("PAYMENT"))
                .andExpect(jsonPath("$.data[0].itemCategory").value("SALES"))
                .andExpect(jsonPath("$.data[0].effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.data[0].effectiveTo").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "GA_ADMIN", "SETTLEMENT", "COMPLIANCE"})
    void allowsEveryAuthenticatedRoleAndReturnsAnEmptyArray(String role) throws Exception {
        LocalDate asOf = LocalDate.of(2025, 12, 31);
        given(commissionItemService.findEffectiveItems(asOf)).willReturn(List.of());

        mockMvc.perform(get("/api/base/commission-items")
                        .param("asOf", "2025-12-31")
                        .with(user("fgc-user").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void rejectsAnInvalidAsOfFormat() throws Exception {
        mockMvc.perform(get("/api/base/commission-items")
                        .param("asOf", "2026/08/04")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @Test
    void rejectsMissingAsOf() throws Exception {
        mockMvc.perform(get("/api/base/commission-items")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("asOf"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/base/commission-items")
                        .param("asOf", "2026-08-04"))
                .andExpect(status().isUnauthorized());
    }
}
