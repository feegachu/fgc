package com.susukkang.fgc.dashboard.controller;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.ShellAdvice;
import com.susukkang.fgc.dashboard.dto.DashboardKpiCounts;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.service.DashboardService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * DASH-W01 화면 + 공통 셸(layout/default.html) + ShellAdvice 를 한 번에 지키는 테스트.
 *
 * 셸이 깨지거나, 푸터 면책문구(COR-009)가 사라지거나, 기준월 advice 가 멎으면 여기서 걸린다.
 * 화면 21개가 전부 이 레이아웃을 쓰므로 이 테스트가 프론트 전체의 회귀 방지선이다.
 */
// GlobalExceptionHandler 는 @ControllerAdvice 라 @WebMvcTest 가 자동으로 끌어온다.
// 그 협력자(FgcMessageResolver·ConstraintErrorCodeResolver)를 같이 넣지 않으면 컨텍스트가 뜨지 않는다.
@WebMvcTest(DashboardViewController.class)
@Import({DashboardViewController.class, ShellAdvice.class, GlobalExceptionHandler.class,
        FgcMessageResolver.class, ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class DashboardViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    /**
     * 셸 사이드바가 sec:authentication="principal.userName" 을 읽으므로 principal 이 반드시
     * FgcUserDetails 여야 한다. user("id").roles(..) 가 만드는 기본 User 로는 렌더링이 깨진다 —
     * 실제 로그인은 FgcUserDetailsService 가 항상 FgcUserDetails 를 돌려주므로 이쪽이 실제와 같다.
     */
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

    private static final FgcUserDetails SETTLE = principal("settle01", "정산담당", "SETTLEMENT");
    private static final FgcUserDetails AUDIT = principal("audit01", "감사담당", "COMPLIANCE");

    private DashboardSummaryResult sampleResult(LocalDate month) {
        return new DashboardSummaryResult(
                month,
                new DashboardKpiCounts(1L, 2L, 3L, 4L, 0L, 6L),
                List.of(new RecentExceptionRow(10L, "CAP_VIOLATION", "CRITICAL", "C001",
                        "1200% 한도 초과", "NEW", OffsetDateTime.parse("2026-07-10T09:00:00+09:00"))),
                List.of(new RecentValidationRunRow(100L, month, 1, "MONTHLY", "COMPLETED", 8, "gaadmin",
                        OffsetDateTime.parse("2026-07-01T01:00:00+09:00"),
                        OffsetDateTime.parse("2026-07-01T01:05:00+09:00"),
                        null, null, null)));
    }

    @Test
    void 대시보드가_공통셸로_렌더링되고_면책문구와_기본_기준월을_담는다() throws Exception {
        given(dashboardService.summarize(LocalDate.of(2026, 7, 1)))
                .willReturn(sampleResult(LocalDate.of(2026, 7, 1)));

        mockMvc.perform(get("/").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/index"))
                // COR-009 — 푸터 면책문구는 지우면 안 된다
                .andExpect(content().string(containsString("교육용 프로토타입입니다")))
                // ShellAdvice 기본 기준월(fgc.demo-month)
                .andExpect(model().attribute("month", "2026-07"))
                .andExpect(content().string(containsString("2026-07")))
                // 사이드바가 서버에서 렌더링됐는지 — 목업의 fgc-shell.js 를 대체한 부분
                .andExpect(content().string(containsString("업무 대시보드")))
                .andExpect(content().string(containsString("FGC-UI-DASH-W01")))
                // KPI 6장이 서비스 값을 그대로 표시하는지
                .andExpect(content().string(containsString("cap_check · result_status = VIOLATION")))
                // 가운뎃점(·)이 들어간 문자열 결합이 실제로 렌더링되는지.
                // IntelliJ 의 Thymeleaf 검사기는 |...| 안의 · 를 토큰으로 잘못 끊어 오류로 표시하지만
                // 런타임은 정상이다. 표현식을 문자열 리터럴 결합으로 바꾼 뒤에도 결과가 같아야 한다.
                .andExpect(content().string(containsString("업무 대시보드 (FGC-UI-DASH-W01) · FGC")))
                .andExpect(content().string(containsString("2026-07-01 · 1회차")))
                .andExpect(content().string(containsString("8/10 단계 · 실행 gaadmin")));
    }

    @Test
    void month_파라미터를_주면_그_달로_집계한다() throws Exception {
        given(dashboardService.summarize(LocalDate.of(2026, 5, 1)))
                .willReturn(sampleResult(LocalDate.of(2026, 5, 1)));

        mockMvc.perform(get("/").param("month", "2026-05").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("month", "2026-05"));

        verify(dashboardService).summarize(LocalDate.of(2026, 5, 1));
    }

    @Test
    void 형식이_틀린_month는_400이_아니라_기본값으로_되돌린다() throws Exception {
        // 헤더 select 가 보내는 값이라 사용자가 직접 칠 일이 없다.
        // 셸이 400 으로 죽으면 화면 전체가 안 뜨므로 조용히 기본월로 복구한다.
        given(dashboardService.summarize(LocalDate.of(2026, 7, 1)))
                .willReturn(sampleResult(LocalDate.of(2026, 7, 1)));

        mockMvc.perform(get("/").param("month", "2026-13").with(user(SETTLE)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("month", "2026-07"));
    }

    @Test
    void COMPLIANCE_역할은_readOnly로_표시된다() throws Exception {
        given(dashboardService.summarize(any())).willReturn(sampleResult(LocalDate.of(2026, 7, 1)));

        mockMvc.perform(get("/").with(user(AUDIT)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("readOnly", true));
    }
}
