package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.validation.dto.ValidationRunDetailResponse;
import com.susukkang.fgc.validation.dto.ValidationRunItemResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunDetailService;
import com.susukkang.fgc.validation.service.ValidationRunSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FGC-UI-VRUN-W01/W02 서버 렌더링 뷰 (FUN-041~043).
 * MPA 규칙: 잘못된 필터는 조용히 정상화, 생성 실패는 500 이 아니라 플래시 배너(PRG).
 */
@WebMvcTest(ValidationRunViewController.class)
@Import({ValidationRunViewController.class, ShellAdvice.class, SecurityConfig.class,
        MessageSourceAutoConfiguration.class, FgcMessageResolver.class, ConstraintErrorCodeResolver.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ValidationRunViewControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ValidationRunSearchService validationRunSearchService;

    @MockitoBean
    private ValidationRunCreateService validationRunCreateService;

    @MockitoBean
    private ValidationRunDetailService validationRunDetailService;

    private static FgcUserDetails userWithRole(long userId, String loginId, String role) {
        AppUserView view = new AppUserView();
        view.setUserId(userId);
        view.setLoginId(loginId);
        view.setPasswordHash("x");
        view.setUserName(loginId);
        view.setRoleCode(role);
        return new FgcUserDetails(view, true, true);
    }

    private static FgcUserDetails settleUser() {
        return userWithRole(1L, "settle01", "SETTLEMENT");
    }

    private static FgcUserDetails gaAdminUser() {
        return userWithRole(3L, "gaadmin01", "GA_ADMIN");
    }

    private ValidationRunListRow listRow(String status, int currentStep) {
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

    private PageResponse<ValidationRunListRow> pageOf(ValidationRunListRow... rows) {
        return PageResponse.of(List.of(rows), 1, 20, rows.length, "validationMonth,desc");
    }

    private ValidationRunDetailResponse detailResponse(String status, int currentStep) {
        return new ValidationRunDetailResponse(
                ValidationRunItemResponse.from(listRow(status, currentStep)),
                List.of(),
                new ValidationRunDetailResponse.TargetSummary(0, 0, 0),
                new ValidationRunDetailResponse.CapSummary(0, 0, 0, 0),
                new ValidationRunDetailResponse.ArbitrageSummary(0, 0, 0),
                new ValidationRunDetailResponse.LedgerSummary(0, 0),
                new ValidationRunDetailResponse.ReconciliationSummary(0, 0, 0, 0, 0, java.math.BigDecimal.ZERO),
                new ValidationRunDetailResponse.ExceptionSummary(49, 0, 49, 0, 0, 49));
    }

    // ── W01 목록 ────────────────────────────────────────────────────

    @Test
    void listRendersRunsFromServer() throws Exception {
        given(validationRunSearchService.search(any(), anyInt(), anyInt()))
                .willReturn(pageOf(listRow("COMPLETED", 8)));
        given(validationRunSearchService.existsActiveMonthlyRun(any())).willReturn(false);

        mvc.perform(get("/validation-runs").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(model().attribute("existsActiveMonthly", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FGC-UI-VRUN-W01")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-07")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("계산완료")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("8/10 단계 (80%)")));
    }

    @Test
    void listDisablesCreateButtonWhenActiveMonthlyRunExists() throws Exception {
        given(validationRunSearchService.search(any(), anyInt(), anyInt())).willReturn(pageOf());
        given(validationRunSearchService.existsActiveMonthlyRun(LocalDate.of(2026, 7, 1))).willReturn(true);

        mvc.perform(get("/validation-runs").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<button[^>]*id=\"btn-create\"[^>]*\\bdisabled\\b[^>]*>.*")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "기준월에 진행 중인 월간 실행이 있어 새로 만들 수 없습니다.")));
    }

    /** MPA 는 업무 예외를 던지지 않는다 — 잘못된 status/page 는 조용히 전체 조회로 되돌린다. */
    @Test
    void listSilentlyNormalizesInvalidStatusAndPage() throws Exception {
        given(validationRunSearchService.search(any(), anyInt(), anyInt())).willReturn(pageOf());
        given(validationRunSearchService.existsActiveMonthlyRun(any())).willReturn(false);

        mvc.perform(get("/validation-runs")
                        .param("status", "BOGUS")
                        .param("page", "-5")
                        .with(user(settleUser())))
                .andExpect(status().isOk());

        ArgumentCaptor<ValidationRunSearchCriteria> captor =
                ArgumentCaptor.forClass(ValidationRunSearchCriteria.class);
        verify(validationRunSearchService).search(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().status()).isNull();
    }

    // ── W01 생성 (PRG) ──────────────────────────────────────────────

    @Test
    void createRedirectsToDetailOnSuccess() throws Exception {
        ValidationRunRow created = new ValidationRunRow();
        created.setValidationRunId(100L);
        created.setValidationMonth(LocalDate.of(2026, 7, 1));
        created.setRunNo(2);
        created.setStatus("CREATED");
        given(validationRunCreateService.create(any())).willReturn(created);

        mvc.perform(post("/validation-runs")
                        .param("month", "2026-07")
                        .param("runType", "MONTHLY")
                        .with(user(settleUser()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/validation-runs/100"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    /** VRUN_001(활성 MONTHLY 중복) — 500 화면이 아니라 목록의 에러 배너로 돌아온다. */
    @Test
    void createRedirectsToListWithErrorBannerOnBusinessError() throws Exception {
        given(validationRunCreateService.create(any()))
                .willThrow(new FgcBusinessException(FgcErrorCode.VRUN_001, Map.of()));

        mvc.perform(post("/validation-runs")
                        .param("month", "2026-07")
                        .param("runType", "MONTHLY")
                        .with(user(settleUser()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/validation-runs"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    void createReturns403ForComplianceRole() throws Exception {
        mvc.perform(post("/validation-runs")
                        .param("month", "2026-07")
                        .param("runType", "MONTHLY")
                        .with(user(userWithRole(2L, "comp01", "COMPLIANCE")))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── W02 상세 ────────────────────────────────────────────────────

    @Test
    void detailRendersHeaderStepperAndExecuteButtonForCreatedRun() throws Exception {
        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("CREATED", 0));
        given(validationRunSearchService.search(any(), anyInt(), anyInt()))
                .willReturn(pageOf(listRow("CREATED", 0)));

        mvc.perform(get("/validation-runs/100").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FGC-UI-VRUN-W02")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-run-status=\"CREATED\"")))
                // SETTLEMENT + CREATED → 실행 버튼 활성
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<button[^>]*id=\"btn-execute\"[^>]*\\bdisabled\\b[^>]*>.*"))))
                // 스텝 이름은 화면 상수 10개
                .andExpect(content().string(org.hamcrest.Matchers.containsString("담당자 검토")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("기존 업무건 재검출")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("미처리 업무건")));
    }

    /** COMPLIANCE 는 조회만 — 실행 버튼은 비활성으로 렌더링된다(화면정의서 :229). */
    @Test
    void detailDisablesExecuteButtonForCompliance() throws Exception {
        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("CREATED", 0));
        given(validationRunSearchService.search(any(), anyInt(), anyInt()))
                .willReturn(pageOf(listRow("CREATED", 0)));

        mvc.perform(get("/validation-runs/100").with(user(userWithRole(2L, "comp01", "COMPLIANCE"))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.matchesPattern(
                        "(?s).*<button[^>]*id=\"btn-execute\"[^>]*\\bdisabled\\b[^>]*>.*")));
    }

    @Test
    void detailExposesFinalizeCapabilityOnlyToFinalizeRoles() throws Exception {
        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("COMPLETED", 8));
        given(validationRunSearchService.search(any(), anyInt(), anyInt()))
                .willReturn(pageOf(listRow("COMPLETED", 8)));

        mvc.perform(get("/validation-runs/100").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-can-finalize=\"false\"")));

        mvc.perform(get("/validation-runs/100").with(user(gaAdminUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-can-finalize=\"true\"")));
    }

    /**
     * FGC-FUN-043 — stepClass 경계 고정. current_step 은 "마지막으로 끝난 단계"라 RUNNING 이면
     * 다음 칸이 진행 중이고, 9단계(사람 검토)는 배치 칸이 아니라 RUNNING/8 에서도 running 이 아니다.
     * FINALIZED 는 ck_validation_run_step 이 10 을 강제하므로 전 칸 done.
     */
    @Test
    void stepClassesFollowStatusAndCurrentStepBoundaries() throws Exception {
        String done = "fgc-stepper__step--done";
        String running = "fgc-stepper__step--running";
        String failed = "fgc-stepper__step--failed";
        given(validationRunSearchService.search(any(), anyInt(), anyInt()))
                .willReturn(pageOf(listRow("RUNNING", 4)));

        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("RUNNING", 4));
        mvc.perform(get("/validation-runs/100").with(user(settleUser())))
                .andExpect(model().attribute("stepClasses", org.hamcrest.Matchers.contains(
                        done, done, done, done, running, "", "", "", "", "")));

        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("RUNNING", 8));
        mvc.perform(get("/validation-runs/100").with(user(settleUser())))
                .andExpect(model().attribute("stepClasses", org.hamcrest.Matchers.contains(
                        done, done, done, done, done, done, done, done, "", "")));

        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("FAILED", 6));
        mvc.perform(get("/validation-runs/100").with(user(settleUser())))
                .andExpect(model().attribute("stepClasses", org.hamcrest.Matchers.contains(
                        done, done, done, done, done, failed, "", "", "", "")));

        given(validationRunDetailService.detail(100L)).willReturn(detailResponse("FINALIZED", 10));
        mvc.perform(get("/validation-runs/100").with(user(settleUser())))
                .andExpect(model().attribute("stepClasses", org.hamcrest.Matchers.contains(
                        done, done, done, done, done, done, done, done, done, done)));
    }

    @Test
    void detailReturns404WhenRunMissing() throws Exception {
        given(validationRunDetailService.detail(999L))
                .willThrow(new FgcBusinessException(FgcErrorCode.COMMON_004));

        mvc.perform(get("/validation-runs/999").with(user(settleUser())))
                .andExpect(status().isNotFound());
    }
}
