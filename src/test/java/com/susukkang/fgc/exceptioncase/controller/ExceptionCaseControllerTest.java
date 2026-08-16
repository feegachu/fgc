package com.susukkang.fgc.exceptioncase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 예외함 API의 요청 바인딩, 인증·인가 및 응답 변환을 검증한다. */
@WebMvcTest(ExceptionCaseController.class)
@Import({ExceptionCaseController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class ExceptionCaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExceptionCaseService exceptionCaseService;

    @Test
    void searchesExceptionsForAuthenticatedUser() throws Exception {
        ExceptionCaseSearchResponse response = new ExceptionCaseSearchResponse(
                List.of(), List.of(), 2, 10, 0, 0,
                "severity,asc,createdAt,desc");
        given(exceptionCaseService.search(any(ExceptionCaseSearchDTO.class), eq(2), eq(10)))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/exceptions")
                        .with(user(principal(3L, "audit01", "COMPLIANCE")))
                        .param("status", "OPEN")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    void performsActionWithAuthenticatedPrincipal() throws Exception {
        ExceptionActionRequest request = new ExceptionActionRequest(
                com.susukkang.fgc.common.code.ExceptionActionType.START_REVIEW,
                "검토를 시작합니다.", "DOC-1");
        ExceptionActionResponse response = new ExceptionActionResponse(
                null, 1, ExceptionStatus.NEW, ExceptionStatus.IN_REVIEW,
                "START_REVIEW", request.reason(), request.evidenceRef(),
                1L, "settle01", OffsetDateTime.now());
        given(exceptionCaseService.action(eq(10L), any(ExceptionActionRequest.class),
                eq(1L), eq("settle01"))).willReturn(response);

        mockMvc.perform(post("/api/v1/exceptions/10/actions")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionSeq").value(1))
                .andExpect(jsonPath("$.data.fromStatus").value("NEW"))
                .andExpect(jsonPath("$.data.toStatus").value("IN_REVIEW"));

        verify(exceptionCaseService).action(
                eq(10L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));
    }

    @Test
    void rejectsActionForReadOnlyRole() throws Exception {
        ExceptionActionRequest request = new ExceptionActionRequest(
                com.susukkang.fgc.common.code.ExceptionActionType.START_REVIEW,
                "검토를 시작합니다.", "DOC-1");

        mockMvc.perform(post("/api/v1/exceptions/10/actions")
                        .with(user(principal(3L, "audit01", "COMPLIANCE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(exceptionCaseService, never()).action(any(), any(), any(), any());
    }

    @Test
    void rejectsActionWhenReasonIsBlank() throws Exception {
        String request = """
                {"actionType":"START_REVIEW","reason":" ","evidenceRef":"DOC-1"}
                """;
        willThrow(new FgcBusinessException(FgcErrorCode.EXCP_001))
                .given(exceptionCaseService)
                .action(eq(10L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));

        mockMvc.perform(post("/api/v1/exceptions/10/actions")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-EXCP-001"));

        verify(exceptionCaseService).action(
                eq(10L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));
    }

    @Test
    void acceptsActionWhenEvidenceRefIsMissing() throws Exception {
        String request = """
                {"actionType":"START_REVIEW","reason":"검토를 시작합니다."}
                """;
        ExceptionActionResponse response = new ExceptionActionResponse(
                null, 1, ExceptionStatus.NEW, ExceptionStatus.IN_REVIEW,
                "START_REVIEW", "검토를 시작합니다.", null,
                1L, "settle01", OffsetDateTime.now());
        given(exceptionCaseService.action(eq(10L), any(ExceptionActionRequest.class),
                eq(1L), eq("settle01"))).willReturn(response);

        mockMvc.perform(post("/api/v1/exceptions/10/actions")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionSeq").value(1))
                .andExpect(jsonPath("$.data.evidenceRef").doesNotExist());

        verify(exceptionCaseService).action(
                eq(10L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));
    }

    @Test
    void rejectsActionWhenReasonExceedsAuditLogLimit() throws Exception {
        ExceptionActionRequest request = new ExceptionActionRequest(
                com.susukkang.fgc.common.code.ExceptionActionType.START_REVIEW,
                "가".repeat(1001),
                null);

        mockMvc.perform(post("/api/v1/exceptions/10/actions")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("reason"));

        verify(exceptionCaseService, never()).action(any(), any(), any(), any());
    }

    @Test
    void returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/exceptions"))
                .andExpect(status().isUnauthorized());
    }

    private FgcUserDetails principal(Long userId, String loginId, String roleCode) {
        AppUserView view = new AppUserView();
        view.setUserId(userId);
        view.setLoginId(loginId);
        view.setPasswordHash("{bcrypt}dummy");
        view.setUserName(loginId);
        view.setRoleCode(roleCode);
        return new FgcUserDetails(view, true, true);
    }
}
