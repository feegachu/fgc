package com.susukkang.fgc.reconciliation.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;
import com.susukkang.fgc.reconciliation.service.ReconciliationRunHistoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FGC-UI-RECO-W01 MPA 골격 (#205). MPA 규칙: 잘못된 stage/page는 조용히 정상화한다
 * (ValidationRunViewController/AuditLogViewController 전례).
 */
@WebMvcTest(ReconciliationViewController.class)
@Import({ReconciliationViewController.class, ShellAdvice.class, SecurityConfig.class,
        MessageSourceAutoConfiguration.class, FgcMessageResolver.class, ConstraintErrorCodeResolver.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ReconciliationViewControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReconciliationRunHistoryService reconciliationRunHistoryService;

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

    private ReconciliationRunHistoryResponse historyRow() {
        return new ReconciliationRunHistoryResponse(
                41L, 7L, LocalDate.of(2026, 7, 1), "GA_TO_FC", "GA→설계사",
                1L, "테스트생명", "COMPLETED", "계산완료", null,
                "settle01", null, null, null, null, null,
                10L, 9L, 1L,
                BigDecimal.valueOf(100000), BigDecimal.valueOf(90000), BigDecimal.valueOf(10000),
                BigDecimal.valueOf(90.0));
    }

    private PageResponse<ReconciliationRunHistoryResponse> pageOf(ReconciliationRunHistoryResponse... rows) {
        return PageResponse.of(List.of(rows), 1, 20, rows.length, "createdAt,desc");
    }

    @Test
    void listRendersHistoryFromServer() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), anyInt(), anyInt(), anyString()))
                .willReturn(pageOf(historyRow()));

        mvc.perform(get("/reconciliations").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC-UI-RECO-W01")))
                .andExpect(content().string(containsString("계산완료")))
                .andExpect(content().string(containsString("테스트생명")));
    }

    @Test
    void listRendersEmptyStateWhenNoHistory() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), anyInt(), anyInt(), anyString()))
                .willReturn(pageOf());

        mvc.perform(get("/reconciliations").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("조건에 맞는 자료가 없습니다")));
    }

    /** MPA는 업무 예외를 던지지 않는다 — 잘못된 stage는 조용히 전체 조회로 되돌린다. */
    @Test
    void listSilentlyNormalizesInvalidStage() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), anyInt(), anyInt(), anyString()))
                .willReturn(pageOf());

        mvc.perform(get("/reconciliations")
                        .param("stage", "BOGUS")
                        .with(user(settleUser())))
                .andExpect(status().isOk());

        ArgumentCaptor<ReconciliationRunSearchCriteria> captor =
                ArgumentCaptor.forClass(ReconciliationRunSearchCriteria.class);
        verify(reconciliationRunHistoryService).findHistory(captor.capture(), eq(1), eq(20), eq("createdAt,desc"));
        assertThat(captor.getValue().paymentStage()).isNull();
    }

    /** SETTLEMENT는 실행·예외생성 버튼이 활성 렌더링된다. */
    @Test
    void listEnablesActionButtonsForSettlement() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), anyInt(), anyInt(), anyString()))
                .willReturn(pageOf());

        mvc.perform(get("/reconciliations").with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(matchesPattern(
                        "(?s).*<button[^>]*id=\"btn-run\"[^>]*\\bdisabled\\b[^>]*>.*"))));
    }

    /** COMPLIANCE는 조회만 — 실행·예외생성 버튼이 비활성 렌더링된다(화면정의서 §4-1). */
    @Test
    void listDisablesActionButtonsForCompliance() throws Exception {
        given(reconciliationRunHistoryService.findHistory(any(), anyInt(), anyInt(), anyString()))
                .willReturn(pageOf());

        mvc.perform(get("/reconciliations").with(user(userWithRole(2L, "comp01", "COMPLIANCE"))))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<button[^>]*id=\"btn-run\"[^>]*\\bdisabled\\b[^>]*>.*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<button[^>]*id=\"btn-bulk-exception\"[^>]*\\bdisabled\\b[^>]*>.*")));
    }

    @Test
    void unauthenticatedUserCannotViewList() throws Exception {
        mvc.perform(get("/reconciliations"))
                .andExpect(status().is3xxRedirection());
    }
}
