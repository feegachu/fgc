package com.susukkang.fgc.exceptioncase.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
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
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.exceptioncase.service.JournalCorrectionExceptionActionService;
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

    @MockitoBean
    private JournalCorrectionExceptionActionService journalCorrectionExceptionActionService;

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
    void bindsValidationRunAndMultiplePolicyTypesForFinalizeChecklistLink() throws Exception {
        ExceptionCaseSearchResponse response = new ExceptionCaseSearchResponse(
                List.of(), List.of(), 1, 20, 0, 0,
                "severity,asc,createdAt,desc");
        given(exceptionCaseService.search(any(ExceptionCaseSearchDTO.class), eq(1), eq(20)))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/exceptions")
                        .with(user(principal(3L, "audit01", "COMPLIANCE")))
                        .param("validationRunId", "44")
                        .param("types", "POLICY_MISSING", "POLICY_DUPLICATE")
                        .param("status", "OPEN"))
                .andExpect(status().isOk());

        verify(exceptionCaseService).search(
                org.mockito.ArgumentMatchers.argThat(criteria ->
                        Long.valueOf(44L).equals(criteria.getValidationRunId())
                                && criteria.getTypes().equals(List.of(
                                ExceptionType.POLICY_MISSING, ExceptionType.POLICY_DUPLICATE))),
                eq(1), eq(20));
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
    void correctsJournalAndResolvesExceptionThroughDedicatedApi() throws Exception {
        JournalCorrectionActionResponse response = new JournalCorrectionActionResponse(
                2, ExceptionStatus.IN_REVIEW, ExceptionStatus.RESOLVED, "CORRECT",
                "금액 정정", "DOC-10", 1L, "settle01", OffsetDateTime.now(),
                10L, 21L, 22L, "JCG-10-test");
        given(journalCorrectionExceptionActionService.correct(
                eq(30L), any(JournalCorrectionActionRequest.class), eq(1L), eq("settle01")))
                .willReturn(response);

        String request = """
                {
                  "reason":"금액 정정",
                  "evidenceRef":"DOC-10",
                  "journalDate":"2026-08-20",
                  "description":"재기표",
                  "lines":[
                    {"originalLineNo":1,"accountCode":"CONFIRMED_PAYOUT_EXPENSE","debitAmount":1000,"creditAmount":0},
                    {"originalLineNo":2,"accountCode":"CONFIRMED_PAYOUT_PAYABLE","debitAmount":0,"creditAmount":1000}
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/exceptions/30/journal-correction")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data.originalJournalHeaderId").value(10L))
                .andExpect(jsonPath("$.data.reversalJournalHeaderId").value(21L))
                .andExpect(jsonPath("$.data.repostedJournalHeaderId").value(22L));
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
    void returns409WhenActionConflictsWithCurrentStatus() throws Exception {
        ExceptionActionRequest request = new ExceptionActionRequest(
                com.susukkang.fgc.common.code.ExceptionActionType.RESOLVE,
                "현재 상태에서 해결을 요청합니다.",
                null);
        willThrow(new FgcBusinessException(
                FgcErrorCode.EXCP_003,
                java.util.Map.of("status", "NEW", "actionType", "RESOLVE")))
                .given(exceptionCaseService)
                .action(eq(10L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));

        mockMvc.perform(post("/api/v1/exceptions/10/actions")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-EXCP-003"));

        verify(exceptionCaseService).action(
                eq(10L), any(ExceptionActionRequest.class), eq(1L), eq("settle01"));
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
