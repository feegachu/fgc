package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunDetailResponse;
import com.susukkang.fgc.validation.dto.ValidationRunItemResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunProgressResponse;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunDetailService;
import com.susukkang.fgc.validation.service.ValidationRunExecuteService;
import com.susukkang.fgc.validation.service.ValidationRunFinalizationService;
import com.susukkang.fgc.validation.service.ValidationRunSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : FGC-FUN-042·043 IF-API-47·48·49 웹 슬라이스 테스트
 *
 * @author yslee
 * @since 2026-08-19
 * @version 1.2
 */
@WebMvcTest(ValidationRunController.class)
@Import({ValidationRunController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class ValidationRunDetailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ValidationRunCreateService validationRunCreateService;

    @MockitoBean
    private ValidationRunSearchService validationRunSearchService;

    @MockitoBean
    private ValidationRunDetailService validationRunDetailService;

    @MockitoBean
    private ValidationRunExecuteService validationRunExecuteService;

    // 2026-08-19 yslee - 확정 API 추가 후 기존 컨트롤러 슬라이스 의존성 보완
    // 기존 코드: 상세·실행·진행률 서비스만 mock으로 등록
    // 문제: 동일 컨트롤러 생성자에 확정 서비스가 추가되어 웹 슬라이스 컨텍스트 생성 실패
    // 개선: 이 테스트에서 직접 사용하지 않아도 생성자 의존성을 충족하도록 mock 등록
    @MockitoBean
    private ValidationRunFinalizationService validationRunFinalizationService;

    private FgcUserDetails settlementPrincipal() {
        AppUserView appUserView = new AppUserView();
        appUserView.setUserId(1L);
        appUserView.setLoginId("settle01");
        appUserView.setPasswordHash("{bcrypt}dummy");
        appUserView.setUserName("정산담당자");
        appUserView.setRoleCode("SETTLEMENT");
        return new FgcUserDetails(appUserView, true, true);
    }

    private ValidationRunListRow headerRow(String status, int currentStep) {
        ValidationRunListRow row = new ValidationRunListRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 7, 1));
        row.setRunNo(1);
        row.setRunType("MONTHLY");
        row.setStatus(status);
        row.setCurrentStep(currentStep);
        row.setTriggeredBy("settle01");
        return row;
    }

    private ValidationRunDetailResponse detailResponse(String status, int currentStep) {
        return new ValidationRunDetailResponse(
                ValidationRunItemResponse.from(headerRow(status, currentStep)),
                List.of(),
                new ValidationRunDetailResponse.TargetSummary(3, 1, 1),
                new ValidationRunDetailResponse.CapSummary(6, 1, 2, 0),
                new ValidationRunDetailResponse.ArbitrageSummary(3, 1, 0),
                new ValidationRunDetailResponse.LedgerSummary(4, 0),
                new ValidationRunDetailResponse.ReconciliationSummary(8, 2, 6, 2, 0, java.math.BigDecimal.valueOf(15000)));
    }

    private ValidationRunRow createdRow() {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 7, 1));
        row.setRunNo(1);
        row.setRunType("MONTHLY");
        row.setStatus("CREATED");
        row.setCurrentStep(0);
        row.setTriggeredBy(1L);
        return row;
    }

    // ── IF-API-47 상세 ──────────────────────────────────────────────

    @Test
    void detailReturnsHeaderTargetsAndSummaries() throws Exception {
        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("COMPLETED", 8));

        mockMvc.perform(get("/api/v1/validation-runs/100").with(user(settlementPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.header.validationRunId").value(100))
                .andExpect(jsonPath("$.data.header.statusLabel").value("계산완료"))
                .andExpect(jsonPath("$.data.header.runTypeLabel").value("월간"))
                .andExpect(jsonPath("$.data.targets").isArray())
                .andExpect(jsonPath("$.data.capSummary.violationCount").value(1))
                .andExpect(jsonPath("$.data.ledgerSummary.imbalanceCount").value(0))
                .andExpect(jsonPath("$.data.reconciliationSummary.mismatchCount").value(2))
                // FGC-FUN-043 확대 — 대사 3분류(MATCHED/MISMATCHED/UNMATCHED)와 차액 합계도
                // API 계약에 노출돼야 한다(코드리뷰 반영).
                .andExpect(jsonPath("$.data.reconciliationSummary.matchedCount").value(6))
                .andExpect(jsonPath("$.data.reconciliationSummary.mismatchedCount").value(2))
                .andExpect(jsonPath("$.data.reconciliationSummary.unmatchedCount").value(0))
                .andExpect(jsonPath("$.data.reconciliationSummary.differenceAmountTotal").value(15000));
    }

    @Test
    void detailReturns404WhenRunNotFound() throws Exception {
        given(validationRunDetailService.detail(999L))
                .willThrow(new FgcBusinessException(FgcErrorCode.COMMON_004));

        mockMvc.perform(get("/api/v1/validation-runs/999").with(user(settlementPrincipal())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
    }

    /** 조회는 전체 역할 — COMPLIANCE 도 볼 수 있다(화면정의서 :1485 "전체 조회"). */
    @Test
    void detailAllowsComplianceRole() throws Exception {
        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("COMPLETED", 8));

        mockMvc.perform(get("/api/v1/validation-runs/100")
                        .with(user("comp01").roles("COMPLIANCE")))
                .andExpect(status().isOk());
    }

    // ── IF-API-48 실행 ──────────────────────────────────────────────

    @Test
    void executeReturns202WithSpecLiteralRunningStatus() throws Exception {
        given(validationRunExecuteService.execute(eq(100L), eq(1L), anyString())).willReturn(createdRow());

        mockMvc.perform(post("/api/v1/validation-runs/100/execute").with(user(settlementPrincipal())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.validationRunId").value(100))
                // 202 바디의 status는 스펙 리터럴 "RUNNING"(§4-2) — DB 진실은 IF-API-49 폴링이 본다
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.statusLabel").value("실행중"))
                .andExpect(jsonPath("$.data.totalSteps").value(10));
    }

    @Test
    void executeReturns403ForComplianceRole() throws Exception {
        mockMvc.perform(post("/api/v1/validation-runs/100/execute")
                        .with(user("comp01").roles("COMPLIANCE")))
                .andExpect(status().isForbidden());

        verify(validationRunExecuteService, never()).execute(anyLong(), anyLong(), anyString());
    }

    /** 실행은 SETTLEMENT(·SYSTEM_ADMIN) — GA_ADMIN 은 확정 담당이지 실행 담당이 아니다(§4-1). */
    @Test
    void executeReturns403ForGaAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/validation-runs/100/execute")
                        .with(user("gaadmin").roles("GA_ADMIN")))
                .andExpect(status().isForbidden());

        verify(validationRunExecuteService, never()).execute(anyLong(), anyLong(), anyString());
    }

    @Test
    void executeReturns409ForFinalizedRun() throws Exception {
        given(validationRunExecuteService.execute(eq(100L), eq(1L), anyString()))
                .willThrow(new FgcBusinessException(FgcErrorCode.VRUN_003));

        mockMvc.perform(post("/api/v1/validation-runs/100/execute").with(user(settlementPrincipal())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-VRUN-003"));
    }

    @Test
    void executeReturns409ForNonCreatedRun() throws Exception {
        given(validationRunExecuteService.execute(eq(100L), eq(1L), anyString()))
                .willThrow(new FgcBusinessException(FgcErrorCode.VRUN_004,
                        Map.of("from", "RUNNING", "to", "RUNNING")));

        mockMvc.perform(post("/api/v1/validation-runs/100/execute").with(user(settlementPrincipal())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-VRUN-004"));
    }

    // ── IF-API-49 진행률 ────────────────────────────────────────────

    @Test
    void progressReturnsCurrentStepBasedPercentage() throws Exception {
        ValidationRunRow running = createdRow();
        running.setStatus("RUNNING");
        running.setCurrentStep(4);
        given(validationRunDetailService.progress(100L))
                .willReturn(ValidationRunProgressResponse.from(running));

        mockMvc.perform(get("/api/v1/validation-runs/100/progress").with(user(settlementPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.currentStep").value(4))
                .andExpect(jsonPath("$.data.totalSteps").value(10))
                .andExpect(jsonPath("$.data.progressPct").value(40))
                .andExpect(jsonPath("$.data.failedStep").doesNotExist());
    }

    @Test
    void progressExposesFailedStepAndMessageForFailedRun() throws Exception {
        ValidationRunRow failed = createdRow();
        failed.setStatus("FAILED");
        failed.setCurrentStep(6);
        failed.setFailureMessage("원장 불균형 1건");
        given(validationRunDetailService.progress(100L))
                .willReturn(ValidationRunProgressResponse.from(failed));

        mockMvc.perform(get("/api/v1/validation-runs/100/progress").with(user(settlementPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failedStep").value(6))
                .andExpect(jsonPath("$.data.failureMessage").value("원장 불균형 1건"));
    }

    /** 진행률 경계 — CREATED/0→0%, COMPLETED/8→80%, FINALIZED/10→100% (제43조 current_step/10). */
    @Test
    void progressCoversBoundaryPercentages() throws Exception {
        ValidationRunRow created = createdRow(); // CREATED, currentStep 0

        ValidationRunRow completed = createdRow();
        completed.setStatus("COMPLETED");
        completed.setCurrentStep(8);

        ValidationRunRow finalized = createdRow();
        finalized.setStatus("FINALIZED");
        finalized.setCurrentStep(10);

        given(validationRunDetailService.progress(100L)).willReturn(
                ValidationRunProgressResponse.from(created),
                ValidationRunProgressResponse.from(completed),
                ValidationRunProgressResponse.from(finalized));

        mockMvc.perform(get("/api/v1/validation-runs/100/progress").with(user(settlementPrincipal())))
                .andExpect(jsonPath("$.data.progressPct").value(0))
                .andExpect(jsonPath("$.data.failedStep").doesNotExist());
        mockMvc.perform(get("/api/v1/validation-runs/100/progress").with(user(settlementPrincipal())))
                .andExpect(jsonPath("$.data.progressPct").value(80));
        mockMvc.perform(get("/api/v1/validation-runs/100/progress").with(user(settlementPrincipal())))
                .andExpect(jsonPath("$.data.progressPct").value(100));
    }

    @Test
    void progressReturns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/validation-runs/100/progress"))
                .andExpect(status().isUnauthorized());
    }
}
