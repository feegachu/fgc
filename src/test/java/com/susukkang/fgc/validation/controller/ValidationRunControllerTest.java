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
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private ValidationRunRow createdRow() {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(100L);
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
}
