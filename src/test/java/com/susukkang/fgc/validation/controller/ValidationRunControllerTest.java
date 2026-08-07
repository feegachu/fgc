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
 * ValidationRunController(IF-API-45) API 통합테스트. ValidationRunCreateService는 mock으로
 * 대체해 컨트롤러의 요청 파싱·인증/인가·응답 변환만 검증한다 — 생성 로직 자체(중복 방지,
 * run_no 채번)는 ValidationRunCreateServiceImplTest·ValidationRunMapperIntegrationTest가
 * 담당한다. CapCheckControllerTest를 참고 패턴으로 삼을 것.
 *
 * POST 바디가 필요하니 ObjectMapper로 CreateValidationRunRequest를 JSON 문자열로 만들어
 *   mockMvc.perform(post("/api/v1/validation-runs")
 *           .with(user("settle01").roles("SETTLEMENT"))
 *           .contentType(MediaType.APPLICATION_JSON)
 *           .content(objectMapper.writeValueAsString(request)))
 * 형태로 호출한다.
 *
 * SecurityConfig를 @Import에 추가한 이유: @WebMvcTest는 컨트롤러/web 계층 빈만 자동으로
 * 스캔하고 SecurityConfig 같은 일반 @Configuration은 기본적으로 로드하지 않는다. 그러면
 * Spring Boot의 기본 시큐리티 자동설정(CSRF 켜짐)이 대신 적용되는데, 실제 운영은
 * apiSecurityFilterChain이 /api/**의 CSRF를 꺼둔다(SecurityConfig 참고). 이 차이 때문에
 * SecurityConfig 없이 POST 테스트를 돌리면 인증·역할이 다 맞아도 CSRF 토큰이 없다는
 * 이유로 전부 403이 난다 — GET만 쓰던 CapCheckControllerTest 등에서는 CSRF 대상이 아니라
 * 이 문제가 드러나지 않았을 뿐이다. SecurityConfig를 그대로 가져오면 테스트가 실제 운영
 * 설정과 같은 규칙으로 돈다.
 *
 * 컨트롤러 메서드 본문까지 도달하는 테스트(정상 생성, 409)는 user("id").roles(...) 대신
 * user(FgcUserDetails)를 쓴다 — Controller가 @AuthenticationPrincipal FgcUserDetails로
 * principal.getUserId()를 꺼내는데, user("id").roles(...)가 만드는 principal은
 * Spring Security의 평범한 User라 FgcUserDetails로 캐스팅이 안 되고(타입 불일치 시
 * @AuthenticationPrincipal은 예외 없이 조용히 null을 넣는다) principal이 null이 되어
 * NullPointerException(→500)이 난다. 401/403처럼 컨트롤러 본문에 도달하기 전에 걸러지는
 * 테스트는 principal을 안 쓰므로 기존 user("id").roles(...)로 충분하다.
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

    // FgcUserDetailsServiceTest·AuthAuditListener가 principal에서 꺼내는 userId까지 필요한
    // 테스트에서 쓴다 — AppUserView → FgcUserDetails 생성자 그대로.
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
    // validationMonth 형식이 틀리면(예: "2026/08") 400. Service는 아예 호출되면 안 된다.
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
    // 인증 없이 호출하면 401 — .with(user(...)) 없이 호출.
    // ApiResponse JSON 바디가 없을 수 있다(SecurityConfig의 HttpStatusEntryPoint가 처리) —
    // status()만 확인하면 충분하다. AuthLoginFlowTest#anonymousApiRequestReturnsUnauthorized 참고.
    void returns401WhenUnauthenticated() throws Exception {
        CreateValidationRunRequest request = new CreateValidationRunRequest("2026-08", "MONTHLY");

        mockMvc.perform(post("/api/v1/validation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    // SETTLEMENT가 아닌 역할로 호출하면 403. 이것도 JSON 바디 없이 status()만 확인
    // (클래스 상단 주석의 AccessDeniedHandler 미설정 설명 참고).
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
