package com.susukkang.fgc.audit.controller;

import com.susukkang.fgc.audit.dto.AuditLogResponse;
import com.susukkang.fgc.audit.service.AuditLogQueryService;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUN-061 IF-API-52 감사로그 조회 API 슬라이스 테스트.
 * 인수조건(FUN-002): COMPLIANCE·SYSTEM_ADMIN 만 200, 그 외 역할은 직접 호출도 403.
 */
@WebMvcTest(AuditLogController.class)
@Import({AuditLogController.class, GlobalExceptionHandler.class, SecurityConfig.class})
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    private static AuditLogResponse sampleLog() {
        return new AuditLogResponse(
                1L,
                OffsetDateTime.parse("2026-08-16T10:00:00+09:00"),
                12L,
                "settle01",
                "PAYMENT_CONFIRMED",
                "COMMISSION_PAYMENT",
                "42",
                "{\"status\":\"DRAFT\"}",
                "{\"status\":\"CONFIRMED\"}",
                null,
                "20260816-1a2b3c",
                3L
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLIANCE", "SYSTEM_ADMIN"})
    void returnsAuditLogsForAllowedRoles(String role) throws Exception {
        given(auditLogQueryService.search(
                eq("COMMISSION_PAYMENT"), eq("42"), eq(12L), eq("PAYMENT_CONFIRMED"),
                eq(LocalDate.parse("2026-08-01")), eq(LocalDate.parse("2026-08-16")), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(sampleLog()), 1, 20, 1, "occurredAt,desc"));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("entityType", "COMMISSION_PAYMENT")
                        .param("entityId", "42")
                        .param("userId", "12")
                        .param("action", "PAYMENT_CONFIRMED")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-16")
                        .with(user("audit01").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].auditLogId").value(1))
                .andExpect(jsonPath("$.data.content[0].userLoginId").value("settle01"))
                .andExpect(jsonPath("$.data.content[0].actionCode").value("PAYMENT_CONFIRMED"))
                .andExpect(jsonPath("$.data.content[0].entityType").value("COMMISSION_PAYMENT"))
                .andExpect(jsonPath("$.data.content[0].entityId").value("42"))
                .andExpect(jsonPath("$.data.content[0].beforeValue").value("{\"status\":\"DRAFT\"}"))
                .andExpect(jsonPath("$.data.content[0].afterValue").value("{\"status\":\"CONFIRMED\"}"))
                .andExpect(jsonPath("$.data.content[0].requestId").value("20260816-1a2b3c"))
                .andExpect(jsonPath("$.data.content[0].policyVersionId").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.requestId", matchesPattern("^\\d{8}-[0-9a-f]{6}$")));

        verify(auditLogQueryService).search(
                "COMMISSION_PAYMENT", "42", 12L, "PAYMENT_CONFIRMED",
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-16"), 1, 20);
    }

    /** 배치 발 감사행은 userLoginId 가 null 로 내려간다 — "BATCH" 표기는 화면 책임. */
    @Test
    void returnsNullUserLoginIdForBatchRows() throws Exception {
        AuditLogResponse batchRow = new AuditLogResponse(
                2L, OffsetDateTime.parse("2026-08-16T02:00:00+09:00"), null, null,
                "VALIDATION_RUN_STARTED", "VALIDATION_RUN", "7", null, null, null, "batch-req", null);
        given(auditLogQueryService.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .willReturn(PageResponse.of(List.of(batchRow), 1, 20, 1, "occurredAt,desc"));

        mockMvc.perform(get("/api/v1/audit-logs").with(user("audit01").roles("COMPLIANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").isEmpty())
                .andExpect(jsonPath("$.data.content[0].userLoginId").isEmpty());
    }

    /** FUN-002 인수조건: 권한 없는 역할은 직접 API 호출도 403 으로 차단된다. */
    @ParameterizedTest
    @ValueSource(strings = {"SETTLEMENT", "GA_ADMIN"})
    void rejectsRolesWithoutAuditPermission(String role) throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").with(user("fgc-user").roles(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidDateRange() throws Exception {
        given(auditLogQueryService.search(any(), any(), any(), any(),
                eq(LocalDate.parse("2026-08-16")), eq(LocalDate.parse("2026-08-01")), anyInt(), anyInt()))
                .willThrow(new FgcBusinessException(
                        FgcErrorCode.COMMON_002, "from", Map.of("field", "from"), null));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("from", "2026-08-16")
                        .param("to", "2026-08-01")
                        .with(user("audit01").roles("COMPLIANCE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("from"));
    }
}
