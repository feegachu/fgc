package com.susukkang.fgc.validation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.validation.dto.CreateValidationRunRequest;
import com.susukkang.fgc.validation.dto.FinalizeChecklistConditionResponse;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunFinalizationService;
import com.susukkang.fgc.validation.service.ValidationRunSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.List;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ValidationRunController(IF-API-45) API 통합테스트
 * ValidationRunCreateService는 mock으로 대체해 컨트롤러의 요청 파싱·인증/인가·응답 변환만 검증
 */
@WebMvcTest(ValidationRunController.class)
@Import({ValidationRunController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class ValidationRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ValidationRunCreateService validationRunCreateService;

    @MockitoBean
    private ValidationRunFinalizationService validationRunFinalizationService;

    // 컨트롤러 생성자가 두 서비스를 다 필요로 하므로 이 테스트에서 안 쓰더라도 빈으로 있어야 한다
    @MockitoBean
    private ValidationRunSearchService validationRunSearchService;

    private ValidationRunRow createdRow() {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(100L);
        row.setRunNo(1);
        row.setStatus("CREATED");
        return row;
    }

    // FgcUserDetailsServiceTest·AuthAuditListener가 principal에서 꺼내는 userId까지 필요한 테스트에서 씀
    private FgcUserDetails settlementPrincipal() {
        AppUserView appUserView = new AppUserView();
        appUserView.setUserId(1L);
        appUserView.setLoginId("settle01");
        appUserView.setPasswordHash("{bcrypt}dummy");
        appUserView.setUserName("정산담당자");
        appUserView.setRoleCode("SETTLEMENT");
        return new FgcUserDetails(appUserView, true, true);
    }

    private FgcUserDetails principal(long userId, String loginId, String roleCode) {
        AppUserView appUserView = new AppUserView();
        appUserView.setUserId(userId);
        appUserView.setLoginId(loginId);
        appUserView.setPasswordHash("{bcrypt}dummy");
        appUserView.setUserName(loginId);
        appUserView.setRoleCode(roleCode);
        return new FgcUserDetails(appUserView, true, true);
    }

    /** FGC-FUN-002 — SYSTEM_ADMIN 은 "전부"(화면정의서 §4-1)라 실행 생성도 허용된다. */
    @Test
    void createsValidationRunForSystemAdmin() throws Exception {
        given(validationRunCreateService.create(any())).willReturn(createdRow());

        AppUserView adminView = new AppUserView();
        adminView.setUserId(4L);
        adminView.setLoginId("admin");
        adminView.setPasswordHash("{bcrypt}dummy");
        adminView.setUserName("시스템관리자");
        adminView.setRoleCode("SYSTEM_ADMIN");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user(new FgcUserDetails(adminView, true, true)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateValidationRunRequest("2026-08", "MONTHLY"))))
                .andExpect(status().isCreated());
    }

    @Test
    // 정상 요청 → 201 + validationRunId/status(CREATED) 응답 확인
    void createsValidationRunAndReturns201() throws Exception {
        given(validationRunCreateService.create(any())).willReturn(createdRow());

        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user(settlementPrincipal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.validationRunId").value(100))
                .andExpect(jsonPath("$.data.runNo").value(1))
                .andExpect(jsonPath("$.data.status").value("CREATED"));
    }

    @Test
    // validationMonth 형식이 틀리면 400
    void returns400ForInvalidValidationMonth() throws Exception {
        CreateValidationRunRequest request = new CreateValidationRunRequest("2026/08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(validationRunCreateService, never()).create(any());
    }

    @Test
    // runType이 enum에 없는 값이면(예: "BOGUS") 400
    void returns400ForInvalidRunType() throws Exception {
        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "BOGUS");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(validationRunCreateService, never()).create(any());
    }

    @Test
    // 인증 없이 호출하면 401 — .with(user(...)) 없이 호출
    // ApiResponse JSON 바디가 없을 수 있다(SecurityConfig의 HttpStatusEntryPoint가 처리)
    void returns401WhenUnauthenticated() throws Exception {
        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    // SETTLEMENT가 아닌 역할로 호출하면 403. 이것도 JSON 바디 없이 status()만 확인
    void returns403ForWrongRole() throws Exception {
        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user("someone").roles("GA_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    /** FUN-002(#82) — COMPLIANCE는 §4-1 "조회만"이라 검증 실행 생성도 403이어야 한다. */
    @Test
    void returns403ForComplianceRole() throws Exception {
        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user("comp01").roles("COMPLIANCE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    // Service가 VRUN_001(FgcBusinessException)을 던지면 409로 매핑되는지
    void returns409WhenServiceThrowsAlreadyRunning() throws Exception {
        given(validationRunCreateService.create(any()))
                .willThrow(new FgcBusinessException(FgcErrorCode.VRUN_001, Map.of()));

        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .with(user(settlementPrincipal()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void checklistIsReadableBySettlementRole() throws Exception {
        given(validationRunFinalizationService.getChecklist(100L)).willReturn(
                new FinalizeChecklistResponse(100L, true, List.of(
                        new FinalizeChecklistConditionResponse(
                                1, "검증 실행 상태가 계산완료(COMPLETED)인가", true, 0, "/validation-runs/100"))));

        mockMvc.perform(get("/api/v1/validation-runs/100/finalize-checklist")
                        .with(user(settlementPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validationRunId").value(100))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.conditions[0].no").value(1));
    }

    @Test
    void systemAdminCanFinalizeWithIdempotencyKey() throws Exception {
        OffsetDateTime finalizedAt = OffsetDateTime.parse("2026-08-16T12:34:56+09:00");
        given(validationRunFinalizationService.finalizeRun(100L, 4L, "finalize-100"))
                .willReturn(new FinalizeValidationRunResponse("FINALIZED", finalizedAt, "admin"));

        mockMvc.perform(post("/api/v1/validation-runs/100/finalize")
                        .header("Idempotency-Key", "finalize-100")
                        .with(user(principal(4L, "admin", "SYSTEM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"))
                .andExpect(jsonPath("$.data.finalizedBy").value("admin"))
                .andExpect(jsonPath("$.data.finalizedAt").value("2026-08-16T12:34:56+09:00"));
    }

    @Test
    void gaAdminCanFinalize() throws Exception {
        given(validationRunFinalizationService.finalizeRun(100L, 2L, "ga-finalize-100"))
                .willReturn(new FinalizeValidationRunResponse(
                        "FINALIZED", OffsetDateTime.parse("2026-08-16T12:34:56+09:00"), "gaadmin"));

        mockMvc.perform(post("/api/v1/validation-runs/100/finalize")
                        .header("Idempotency-Key", "ga-finalize-100")
                        .with(user(principal(2L, "gaadmin", "GA_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void settlementCannotFinalize() throws Exception {
        mockMvc.perform(post("/api/v1/validation-runs/100/finalize")
                        .header("Idempotency-Key", "finalize-100")
                        .with(user(settlementPrincipal())))
                .andExpect(status().isForbidden());

        verify(validationRunFinalizationService, never())
                .finalizeRun(anyLong(), anyLong(), anyString());
    }

    @Test
    void finalizeAllowsMissingOptionalIdempotencyKeyHeader() throws Exception {
        given(validationRunFinalizationService.finalizeRun(100L, 4L, null))
                .willReturn(new FinalizeValidationRunResponse(
                        "FINALIZED", OffsetDateTime.parse("2026-08-16T12:34:56+09:00"), "admin"));

        mockMvc.perform(post("/api/v1/validation-runs/100/finalize")
                        .with(user(principal(4L, "admin", "SYSTEM_ADMIN"))))
                .andExpect(status().isOk());

        verify(validationRunFinalizationService).finalizeRun(100L, 4L, null);
    }

}
