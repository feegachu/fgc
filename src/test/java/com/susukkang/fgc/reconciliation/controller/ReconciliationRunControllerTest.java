package com.susukkang.fgc.reconciliation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.reconciliation.dto.CreateReconciliationRunRequest;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunService;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
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

    private static CreateReconciliationRunRequest validRequest() {
        return new CreateReconciliationRunRequest("2026-08", "GA_TO_FC", 1L, 10L);
    }

    private static ReconciliationRunRow createdRow() {
        ReconciliationRunRow row = new ReconciliationRunRow();
        row.setReconciliationRunId(41L);
        row.setStatus("RUNNING");
        return row;
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
