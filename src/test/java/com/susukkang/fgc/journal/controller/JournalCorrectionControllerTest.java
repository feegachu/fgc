package com.susukkang.fgc.journal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.dto.ReverseJournalRequest;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** IF-API-36 요청 검증, CSRF, 역할 및 공통 오류 봉투를 검증한다. */
@WebMvcTest(JournalCorrectionController.class)
@Import({JournalCorrectionController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class JournalCorrectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JournalCorrectionService journalCorrectionService;

    @Test
    void reversesPostedJournalForSettlementRole() throws Exception {
        given(journalCorrectionService.reverse(any(ReverseJournalCommand.class)))
                .willReturn(new JournalCorrectionResult(20L, 10L, null, "JCG-10-test"));

        ReverseJournalRequest request = new ReverseJournalRequest("금액 정정", "DOC-10");
        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.journalHeaderId").value(20L))
                .andExpect(jsonPath("$.data.reversalOfId").value(10L));

        verify(journalCorrectionService).reverse(new ReverseJournalCommand(
                10L, "금액 정정", "DOC-10", 1L));
    }

    @Test
    void allowsGaAdminRole() throws Exception {
        given(journalCorrectionService.reverse(any(ReverseJournalCommand.class)))
                .willReturn(new JournalCorrectionResult(20L, 10L, null, "JCG-10-test"));

        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(user(principal(2L, "gaadmin01", "GA_ADMIN")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"정정\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsReadOnlyComplianceRole() throws Exception {
        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(user(principal(3L, "audit01", "COMPLIANCE")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"정정\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));

        verify(journalCorrectionService, never()).reverse(any());
    }

    @Test
    void requiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"정정\"}"))
                .andExpect(status().isForbidden());

        verify(journalCorrectionService, never()).reverse(any());
    }

    @Test
    void rejectsBlankReasonWithCommonValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("reason"));

        verify(journalCorrectionService, never()).reverse(any());
    }

    @Test
    void returnsConflictForDuplicateReversal() throws Exception {
        willThrow(new FgcBusinessException(FgcErrorCode.LEDG_002))
                .given(journalCorrectionService).reverse(any(ReverseJournalCommand.class));

        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(user(principal(1L, "settle01", "SETTLEMENT")))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"중복 요청\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-LEDG-002"));
    }

    @Test
    void returnsUnauthorizedWhenUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/journals/10/reverse")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"정정\"}"))
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
