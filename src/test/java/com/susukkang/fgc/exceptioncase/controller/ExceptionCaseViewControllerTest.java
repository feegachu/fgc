package com.susukkang.fgc.exceptioncase.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseResponseDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryResponse;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** FGC-FUN-052/053/057 예외함의 필터, 페이지네이션, 행 선택용 상세 데이터 렌더링을 검증한다. */
@WebMvcTest(ExceptionCaseViewController.class)
@Import({ExceptionCaseViewController.class, ShellAdvice.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ExceptionCaseViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExceptionCaseService service;

    private static final FgcUserDetails SETTLE = principal("settle01", "정산담당", "SETTLEMENT");

    @Test
    void defaultsToOpenAndRendersPagedRowsWithSelectableDetails() throws Exception {
        ExceptionCaseResponseDTO row = row(10L, ExceptionStatus.NEW,
                "COMMISSION_TRANSACTION", "77", "1200% 한도 초과");
        given(service.search(argThat(c -> "OPEN".equals(c.getStatus())), eq(1), eq(20)))
                .willReturn(response(List.of(row), 1, 20, 21, 21));

        mockMvc.perform(get("/exceptions").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(view().name("exception/list"))
                .andExpect(model().attribute("statusFilter", "OPEN"))
                .andExpect(model().attribute("openCount", 21L))
                .andExpect(content().string(containsString("data-exception-id=\"10\"")))
                .andExpect(content().string(containsString("id=\"exception-detail-10\"")))
                .andExpect(content().string(containsString("id=\"exception-history-10\"")))
                .andExpect(content().string(containsString("href=\"/transactions\"")))
                .andExpect(content().string(containsString("page=2")))
                .andExpect(content().string(containsString("/js/features/exception/exception-list.js")))
                .andExpect(content().string(containsString("/css/features/exception.css")));
    }

    @Test
    void dashboardOpenFilterAndMonthArePreserved() throws Exception {
        given(service.search(argThat(c -> "OPEN".equals(c.getStatus())), eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions")
                        .param("status", "OPEN").param("month", "2026-05")
                        .with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("month", "2026-05"))
                .andExpect(model().attribute("statusFilter", "OPEN"));
    }

    @Test
    void actualStatusAndRequestedPageAreForwarded() throws Exception {
        given(service.search(argThat(c -> "RESOLVED".equals(c.getStatus())), eq(2), eq(20)))
                .willReturn(response(List.of(row(11L, ExceptionStatus.RESOLVED,
                        "INSURANCE_CONTRACT", "5", "필수값 누락")), 2, 20, 21, 3));

        mockMvc.perform(get("/exceptions")
                        .param("status", "RESOLVED").param("page", "2")
                        .with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "RESOLVED"))
                .andExpect(content().string(containsString("필수값 누락")))
                .andExpect(content().string(containsString("href=\"/contracts/5\"")));
    }

    @Test
    void explicitEmptyStatusMeansAllStatuses() throws Exception {
        given(service.search(argThat(c -> "".equals(c.getStatus())), eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions").param("status", "").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", ""));
    }

    @Test
    void unsupportedStatusFallsBackToOpen() throws Exception {
        given(service.search(argThat(c -> "OPEN".equals(c.getStatus())), eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions").param("status", "NOPE").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "OPEN"));

        verify(service).search(argThat(c -> "OPEN".equals(c.getStatus())), eq(1), eq(20));
    }

    private static ExceptionCaseSearchResponse response(
            List<ExceptionCaseResponseDTO> content, int page, int size, long total, long openCount
    ) {
        List<ExceptionTypeSummaryResponse> summary = openCount == 0
                ? List.of()
                : List.of(new ExceptionTypeSummaryResponse(ExceptionType.DATA_QUALITY, openCount));
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new ExceptionCaseSearchResponse(
                summary, content, page, size, total, totalPages,
                "severity,asc,createdAt,desc");
    }

    private static ExceptionCaseResponseDTO row(
            long id, ExceptionStatus status, String sourceType, String sourceId, String title
    ) {
        return new ExceptionCaseResponseDTO(
                id, "KEY-" + id, ExceptionType.DATA_QUALITY, ExceptionSeverity.WARNING,
                status, title, "상세 설명", "C001", "김정산", null, null,
                sourceType, sourceId, OffsetDateTime.parse("2026-07-10T09:00:00+09:00"), List.of());
    }

    private static FgcUserDetails principal(String loginId, String userName, String roleCode) {
        AppUserView view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId(loginId);
        view.setPasswordHash("{noop}x");
        view.setUserName(userName);
        view.setRoleCode(roleCode);
        view.setAccountStatus("ACTIVE");
        return new FgcUserDetails(view, true, true);
    }
}
