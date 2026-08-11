package com.susukkang.fgc.common.web;

import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.dashboard.controller.DashboardViewController;
import com.susukkang.fgc.dashboard.dto.DashboardKpiCounts;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;

/** 보안 체인 + 실제 대시보드 컨트롤러 + 레이아웃 렌더링까지 태워서 기준월 세션 유지를 확인한다. */
@WebMvcTest(DashboardViewController.class)
@Import({ShellAdvice.class, SecurityConfig.class, MessageSourceAutoConfiguration.class,
        com.susukkang.fgc.common.exception.FgcMessageResolver.class,
        com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ShellMonthSessionWebTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DashboardService dashboardService;

    private static com.susukkang.fgc.auth.dto.FgcUserDetails settleUser() {
        var view = new com.susukkang.fgc.auth.dto.AppUserView();
        view.setUserId(1L);
        view.setLoginId("settle01");
        view.setPasswordHash("x");
        view.setUserName("정산담당");
        view.setRoleCode("SETTLEMENT");
        return new com.susukkang.fgc.auth.dto.FgcUserDetails(view, true, true);
    }

    @Test
    void month_param_persists_into_next_paramless_request() throws Exception {
        given(dashboardService.summarize(any())).willAnswer(inv -> new DashboardSummaryResult(
                (LocalDate) inv.getArgument(0),
                new DashboardKpiCounts(0, 0, 0, 0, 0, 0),
                List.of(), List.of()));

        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/").param("month", "2026-05").session(session).with(user(settleUser())))
                .andExpect(model().attribute("month", "2026-05"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<option value=\"2026-05\" selected=\"selected\"")));

        mvc.perform(get("/").session(session).with(user(settleUser())))
                .andExpect(model().attribute("month", "2026-05"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<option value=\"2026-05\" selected=\"selected\"")));
    }

    /**
     * 인라인 핸들러는 with(document) 스코프에서 돈다 — document.URL(문자열)이 전역 URL 생성자를
     * 가려서 new URL(...) 은 "URL is not a constructor" 로 조용히 죽는다. select 가 리로드를
     * 못 걸면 ?month= 가 서버에 닿지 않아 위 세션 테스트가 통과해도 화면에서는 기준월이 안 바뀐다.
     */
    @Test
    void month_select_reloads_with_unshadowed_url_constructor() throws Exception {
        given(dashboardService.summarize(any())).willAnswer(inv -> new DashboardSummaryResult(
                (LocalDate) inv.getArgument(0),
                new DashboardKpiCounts(0, 0, 0, 0, 0, 0),
                List.of(), List.of()));

        mvc.perform(get("/").with(user(settleUser())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("new window.URL(location)")));
    }

    /**
     * 이 advice 는 @RestController 에도 걸린다. 화면이 자기 필터의 month 로 Ajax(SIR-006)를 부르면
     * 그 값이 헤더 기준월까지 덮어써 버린다 — /api/** 의 ?month= 는 세션을 건드리면 안 된다.
     */
    @Test
    void api_month_param_does_not_overwrite_shell_month() {
        ShellAdvice advice = new ShellAdvice("2026-07");

        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/");
        assertThat(advice.month("2026-05", page)).isEqualTo("2026-05");

        MockHttpSession session = (MockHttpSession) page.getSession(false);
        assertThat(session).isNotNull();

        MockHttpServletRequest ajax = new MockHttpServletRequest("GET", "/api/cap-checks");
        ajax.setSession(session);
        advice.month("2026-01", ajax);

        assertThat(session.getAttribute("fgc.month")).isEqualTo("2026-05");

        MockHttpServletRequest nextPage = new MockHttpServletRequest("GET", "/");
        nextPage.setSession(session);
        assertThat(advice.month(null, nextPage)).isEqualTo("2026-05");
    }
}
