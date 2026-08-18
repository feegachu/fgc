package com.susukkang.fgc.reconciliation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunRequest;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.service.ReconciliationExceptionService;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunHistoryService;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunService;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : IF-API-38 대사 실행 생성 컨트롤러 테스트
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@WebMvcTest(ReconciliationRunController.class)
@Import({ReconciliationRunController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class ReconciliationRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReconciliationRunService reconciliationRunService;

    @MockitoBean
    private ReconciliationResultQueryService reconciliationResultQueryService;

    @MockitoBean
    private ReconciliationExceptionService reconciliationExceptionService;

    @MockitoBean
    private ReconciliationRunHistoryService reconciliationRunHistoryService;

    @Test
    void 대사_실행_이력을_조건_없이_조회한다() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), eq(1), eq(20), eq("createdAt,desc")))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "createdAt,desc"));

        mockMvc.perform(get("/api/v1/reconciliations")
                        .with(user(principal("COMPLIANCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 정산월과_지급단계로_대사_실행_이력을_필터링한다() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), eq(2), eq(10), eq("createdAt,asc")))
                .willReturn(PageResponse.of(List.of(historyRow()), 2, 10, 1, "createdAt,asc"));

        mockMvc.perform(get("/api/v1/reconciliations")
                        .with(user(principal("COMPLIANCE")))
                        .queryParam("month", "2026-07")
                        .queryParam("stage", "GA_TO_FC")
                        .queryParam("page", "2")
                        .queryParam("size", "10")
                        .queryParam("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].reconciliationRunId").value(41))
                .andExpect(jsonPath("$.data.content[0].paymentStageLabel").value("GA→설계사"));

        verify(reconciliationRunHistoryService).findHistory(
                argThat(criteria -> criteria.paymentStage().equals("GA_TO_FC")), eq(2), eq(10), eq("createdAt,asc"));
    }

    @Test
    void 잘못된_기준월로_이력을_조회하면_400으로_거절한다() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliations")
                        .with(user(principal("COMPLIANCE")))
                        .queryParam("month", "2026/07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("settlementMonth"));

        verify(reconciliationRunHistoryService, never()).findHistory(any(), anyInt(), anyInt(), anyString());
    }

    @Test
    void 정의되지_않은_지급단계로_이력을_조회하면_400으로_거절한다() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliations")
                        .with(user(principal("COMPLIANCE")))
                        .queryParam("stage", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("paymentStage"));
    }

    @Test
    void 로그인하지_않으면_대사_실행_이력을_조회할_수_없다() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 유효한_요청은_RUNNING_대사실행을_생성한다() throws Exception {
        given(reconciliationRunService.create(any())).willReturn(createdRow());

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal("SETTLEMENT")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reconciliationRunId").value(41))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    /**
     * FGC-FUN-002 / IF-API-38 — 인터페이스정의서 §2-1의 SYSTEM_ADMIN "전부" 권한을 보장한다.
     * 역할 열의 SETTLEMENT는 SETTLEMENT와 SYSTEM_ADMIN을 함께 뜻하므로 두 역할 모두 처리 권한이 있다.
     */
    @Test
    @DisplayName("SYSTEM_ADMIN도 CSRF 토큰으로 대사 실행을 생성할 수 있다")
    void 시스템관리자는_RUNNING_대사실행을_생성한다() throws Exception {
        given(reconciliationRunService.create(any())).willReturn(createdRow());

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal("SYSTEM_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reconciliationRunId").value(41))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void 잘못된_기준월은_400으로_거절한다() throws Exception {
        CreateReconciliationRunRequest request =
                new CreateReconciliationRunRequest("2026/08", "GA_TO_FC", 1L, 10L);

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal("SETTLEMENT")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("settlementMonth"));

        verify(reconciliationRunService, never()).create(any());
    }

    @Test
    void 정의되지_않은_지급단계는_400으로_거절한다() throws Exception {
        CreateReconciliationRunRequest request =
                new CreateReconciliationRunRequest("2026-08", "UNKNOWN", 1L, 10L);

        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal("SETTLEMENT")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("paymentStage"));
    }

    @Test
    void 정산담당자가_아니면_403으로_차단한다() throws Exception {
        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal("GA_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 세션_요청에_CSRF_토큰이_없으면_403으로_차단한다() throws Exception {
        mockMvc.perform(post("/api/v1/reconciliations")
                        .with(user(principal("SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidResultTypeReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliations/41/results")
                        .with(user(principal("COMPLIANCE")))
                        .queryParam("resultType", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("resultType"));
    }

    @Test
    void unauthenticatedUserCannotReadResultDetail() throws Exception {
        mockMvc.perform(get("/api/v1/reconciliations/results/99"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 정산담당자가_불일치_예외를_일괄_생성한다() throws Exception {
        given(reconciliationExceptionService.bulkCreate(41L))
                .willReturn(new ReconciliationExceptionBulkCreateResponse(2L, 1L));

        mockMvc.perform(post("/api/v1/reconciliations/41/exceptions")
                        .with(user(principal("SETTLEMENT")))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.skippedDuplicate").value(1));
    }

    @Test
    void 존재하지_않는_실행의_예외_일괄_생성은_404로_거절한다() throws Exception {
        given(reconciliationExceptionService.bulkCreate(99L))
                .willThrow(new com.susukkang.fgc.common.exception.FgcBusinessException(
                        com.susukkang.fgc.common.exception.FgcErrorCode.COMMON_004,
                        java.util.Map.of("id", 99L)));

        mockMvc.perform(post("/api/v1/reconciliations/99/exceptions")
                        .with(user(principal("SETTLEMENT")))
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 정산담당자가_아니면_예외_일괄_생성을_403으로_차단한다() throws Exception {
        mockMvc.perform(post("/api/v1/reconciliations/41/exceptions")
                        .with(user(principal("GA_ADMIN")))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verify(reconciliationExceptionService, never()).bulkCreate(41L);
    }

    @Test
    void 예외_일괄_생성_요청에_CSRF_토큰이_없으면_403으로_차단한다() throws Exception {
        mockMvc.perform(post("/api/v1/reconciliations/41/exceptions")
                        .with(user(principal("SETTLEMENT"))))
                .andExpect(status().isForbidden());
    }

    private static CreateReconciliationRunRequest validRequest() {
        return new CreateReconciliationRunRequest("2026-08", "GA_TO_FC", 1L, 10L);
    }

    private static ReconciliationRunRow createdRow() {
        ReconciliationRunRow row = new ReconciliationRunRow();
        row.setReconciliationRunId(41L);
        row.setStatus("RUNNING");
        return row;
    }

    private static ReconciliationRunHistoryResponse historyRow() {
        return new ReconciliationRunHistoryResponse(
                41L, 7L,
                java.time.LocalDate.of(2026, 7, 1), "GA_TO_FC", "GA→설계사",
                1L, "테스트생명",
                "COMPLETED", "계산완료",
                null,
                "settlement-user", null,
                null, null,
                null, null,
                10L, 9L, 1L,
                java.math.BigDecimal.valueOf(100000), java.math.BigDecimal.valueOf(90000), java.math.BigDecimal.valueOf(10000),
                java.math.BigDecimal.valueOf(90.0));
    }

    private static FgcUserDetails principal(String roleCode) {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId("reconciliation-user");
        view.setPasswordHash("{noop}x");
        view.setUserName("대사 실행 담당자");
        view.setRoleCode(roleCode);
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }
}
