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
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionAssigneeRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseResponseDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchResponse;
import com.susukkang.fgc.exceptioncase.dto.ExceptionOccurrenceResponse;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
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
        ExceptionCaseResponseDTO row = row(10L, ExceptionStatus.IN_REVIEW,
                "COMMISSION_TRANSACTION", "77", "1200% 한도 초과", List.of(
                        new ExceptionActionResponse(
                                1L, 1, ExceptionStatus.NEW, ExceptionStatus.IN_REVIEW,
                                "START_REVIEW", "검토 시작", null, 1L, "settle01",
                                OffsetDateTime.parse("2026-07-10T09:00:00+09:00"))));
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
                .andExpect(content().string(containsString("id=\"exception-occurrence-10\"")))
                .andExpect(content().string(containsString("최초 검출 실행")))
                .andExpect(content().string(containsString("최근 검출 실행")))
                .andExpect(content().string(containsString("data-exception-action-form")))
                .andExpect(content().string(containsString("처리 저장")))
                .andExpect(content().string(containsString("href=\"/transactions\"")))
                .andExpect(content().string(containsString("page=2")))
                .andExpect(content().string(containsString("데이터 품질")))
                .andExpect(content().string(containsString("value=\"WARNING\">주의")))
                .andExpect(content().string(containsString("1. 검토 시작")))
                .andExpect(content().string(containsString("<span>신규</span> → <span>검토중</span>")))
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
        // 범위 밖 page 보정은 서비스 책임(count 선행) — 컨트롤러는 요청 page 를 그대로
        // 넘기고 서비스가 돌려준 보정 페이지(casePage.page())를 렌더링한다.
        given(service.search(argThat(c -> c != null && "RESOLVED".equals(c.getStatus())), eq(9), eq(20)))
                .willReturn(response(List.of(row(11L, ExceptionStatus.RESOLVED,
                        "INSURANCE_CONTRACT", "5", "필수값 누락")), 2, 20, 21, 3));

        mockMvc.perform(get("/exceptions")
                        .param("status", "RESOLVED").param("page", "9")
                        .with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "RESOLVED"))
                .andExpect(content().string(containsString("필수값 누락")))
                .andExpect(content().string(containsString("href=\"/contracts/5\"")))
                // 서비스가 보정한 페이지(2)가 화면 페이지 표기에 그대로 반영된다
                .andExpect(content().string(containsString("<span>2</span> /")));

        verify(service).search(argThat(c -> c != null && "RESOLVED".equals(c.getStatus())), eq(9), eq(20));
    }

    @Test
    void assigneeFilterUnassignedMapsToUnassignedOnly() throws Exception {
        given(service.search(argThat(c -> c != null && c.isUnassignedOnly() && c.getAssignee() == null),
                eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions").param("assigneeFilter", "unassigned").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assigneeFilter", "unassigned"));

        verify(service).search(argThat(c -> c.isUnassignedOnly()), eq(1), eq(20));
    }

    @Test
    void assigneeFilterNumberMapsToAssigneeAndSurvivesPaging() throws Exception {
        given(service.assignees()).willReturn(List.of(new ExceptionAssigneeRow(2L, "settle01")));
        given(service.search(argThat(c -> c != null && Long.valueOf(2L).equals(c.getAssignee())),
                eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 41, 0));

        mockMvc.perform(get("/exceptions").param("assigneeFilter", "2").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("assigneeFilter=2")))
                .andExpect(content().string(containsString(">settle01</option>")));
    }

    @Test
    void invalidAssigneeFilterIsSilentlyIgnored() throws Exception {
        given(service.search(argThat(c -> c != null && c.getAssignee() == null && !c.isUnassignedOnly()),
                eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions").param("assigneeFilter", "abc").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assigneeFilter", nullValue()));
    }

    /** VRUN-W02 '예외함 열기' 링크가 보내는 검증월 검색조건 — 셸 기준월 month 와 별개다. */
    @Test
    void validationMonthFilterIsAppliedAndKeptInPagingLinks() throws Exception {
        given(service.validationMonths()).willReturn(List.of(LocalDate.of(2026, 7, 1)));
        given(service.search(argThat(c -> c != null
                        && LocalDate.of(2026, 7, 1).equals(c.getValidationMonth())),
                eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 41, 0));

        mockMvc.perform(get("/exceptions").param("validationMonth", "2026-07-01").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("validationMonth=2026-07-01")))
                .andExpect(content().string(containsString(">2026-07</option>")));
    }

    /** 형식이 깨진 필터는 400 대신 그 조건만 빠진 채 기본 필터로 조회된다. */
    @Test
    void malformedFilterValuesFallBackSilently() throws Exception {
        given(service.search(argThat(c -> c != null && c.getType() == null
                        && c.getValidationMonth() == null),
                eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions")
                        .param("type", "NOT_A_TYPE").param("validationMonth", "not-a-date")
                        .with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("statusFilter", "OPEN"));
    }

    @Test
    void reasonCodeOptionsShowKoreanLabels() throws Exception {
        given(service.reasonCodes()).willReturn(List.of("CAP_LIMIT_VIOLATION"));
        given(service.search(argThat(c -> "OPEN".equals(c.getStatus())), eq(1), eq(20)))
                .willReturn(response(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/exceptions").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">1,200% 한도 초과</option>")));
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
        return row(id, status, sourceType, sourceId, title, List.of());
    }

    @Test
    void searchFiltersAreForwardedAndRemainInSummaryAndPagingLinks() throws Exception {
        given(service.search(argThat(c -> c.getType() == ExceptionType.CAP_WARNING
                        && c.getSeverity() == ExceptionSeverity.HIGH
                        && "OPEN".equals(c.getStatus())
                        && "C004".equals(c.getContractNo())), eq(2), eq(20)))
                .willReturn(response(List.of(), 2, 20, 21, 3));

        mockMvc.perform(get("/exceptions")
                        .param("type", "CAP_WARNING")
                        .param("severity", "HIGH")
                        .param("status", "OPEN")
                        .param("contractNo", "C004")
                        .param("page", "2")
                        .with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("typeFilter", ExceptionType.CAP_WARNING))
                .andExpect(model().attribute("severityFilter", ExceptionSeverity.HIGH))
                .andExpect(model().attribute("contractNoFilter", "C004"))
                .andExpect(content().string(containsString("type=DATA_QUALITY&amp;status=OPEN")))
                .andExpect(content().string(containsString("type=CAP_WARNING&amp;reasonCode=&amp;severity=HIGH&amp;status=OPEN")))
                .andExpect(content().string(containsString("contractNo=C004")));
    }

    private static ExceptionCaseResponseDTO row(
            long id, ExceptionStatus status, String sourceType, String sourceId, String title,
            List<ExceptionActionResponse> actions
    ) {
        return new ExceptionCaseResponseDTO(
                id, "KEY-" + id, ExceptionType.DATA_QUALITY, "FINANCIAL_SNAPSHOT_MISSING",
                ExceptionSeverity.WARNING, status, title, "상세 설명", "C001", "김정산",
                null, null, sourceType, sourceId, LocalDate.of(2026, 7, 1), 1505L, 1506L,
                OffsetDateTime.parse("2026-07-10T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-11T09:00:00+09:00"), 2,
                OffsetDateTime.parse("2026-07-10T09:00:00+09:00"),
                List.of(new ExceptionOccurrenceResponse(
                        id, 1506L, 2, LocalDate.of(2026, 7, 1), "DATA_QUALITY",
                        "FINANCIAL_SNAPSHOT_MISSING", sourceType, sourceId, "{}", false, false,
                        OffsetDateTime.parse("2026-07-11T09:00:00+09:00"))),
                actions);
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
