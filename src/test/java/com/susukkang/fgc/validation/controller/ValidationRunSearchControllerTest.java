package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ValidationRunController#search(#41) API 통합테스트.
 * ValidationRunSearchService는 mock으로 대체해 컨트롤러의 요청 파싱·인증·응답 변환만 검증한다.
 * ValidationRunController#search 구현이 TODO인 동안은 전부 실패(500/red)하는 게 정상이고,
 * 구현을 마치면 이 테스트들이 통과해야 한다.
 */
@WebMvcTest(ValidationRunController.class)
@Import({ValidationRunController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class, SecurityConfig.class})
class ValidationRunSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // 컨트롤러 생성자가 두 서비스를 다 필요로 하므로 이 테스트에서 안 쓰더라도 빈으로 있어야 한다
    @MockitoBean
    private ValidationRunCreateService validationRunCreateService;

    @MockitoBean
    private ValidationRunSearchService validationRunSearchService;

    private ValidationRunListRow sampleRow() {
        ValidationRunListRow row = new ValidationRunListRow();
        row.setValidationRunId(100L);
        row.setValidationMonth(LocalDate.of(2026, 8, 1));
        row.setRunNo(1);
        row.setRunType("MONTHLY");
        row.setStatus("RUNNING");
        row.setCurrentStep(5);
        row.setTriggeredBy("settle01");
        return row;
    }

    @Test
    // 조건 없이 호출 → 200 + 목록 1건
    void searchReturnsPagedContent() throws Exception {
        PageResponse<ValidationRunListRow> page =
                PageResponse.of(List.of(sampleRow()), 1, 20, 1, "validationMonth,desc");
        given(validationRunSearchService.search(any(), eq(1), eq(20))).willReturn(page);

        mockMvc.perform(get("/api/v1/validation-runs").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].validationRunId").value(100))
                .andExpect(jsonPath("$.data.content[0].runType").value("MONTHLY"))
                .andExpect(jsonPath("$.data.content[0].runTypeLabel").value("월정기검증"))
                .andExpect(jsonPath("$.data.content[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.content[0].statusLabel").value("실행중"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    // month·status 조건이 그대로 criteria로 전달되는지
    void searchPassesMonthAndStatusAsCriteria() throws Exception {
        given(validationRunSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "validationMonth,desc"));

        mockMvc.perform(get("/api/v1/validation-runs")
                        .param("month", "2026-08")
                        .param("status", "RUNNING")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk());

        verify(validationRunSearchService).search(
                eq(new ValidationRunSearchCriteria(LocalDate.of(2026, 8, 1), "RUNNING")), eq(1), eq(20));
    }

    @Test
    // 조건에 맞는 실행이 없으면 200 + 빈 목록(에러 아님)
    void searchReturnsEmptyContentWhenNoMatch() throws Exception {
        given(validationRunSearchService.search(any(), eq(1), eq(20)))
                .willReturn(PageResponse.of(List.of(), 1, 20, 0, "validationMonth,desc"));

        mockMvc.perform(get("/api/v1/validation-runs").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    // month 형식이 yyyy-MM이 아니면 400, 서비스 호출 안 함
    void returns400ForInvalidMonthFormat() throws Exception {
        mockMvc.perform(get("/api/v1/validation-runs")
                        .param("month", "2026/08")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest());

        verify(validationRunSearchService, never()).search(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    // status가 enum에 없는 값(예: "BOGUS")이면 400
    void returns400ForInvalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/validation-runs")
                        .param("status", "BOGUS")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest());

        verify(validationRunSearchService, never()).search(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    // 인증 없이 호출하면 401 — CapCheckController#search와 동일한 isAuthenticated() 계약
    void returns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/validation-runs"))
                .andExpect(status().isUnauthorized());
    }
}
