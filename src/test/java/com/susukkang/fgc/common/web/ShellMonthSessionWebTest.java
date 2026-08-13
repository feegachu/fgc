package com.susukkang.fgc.common.web;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
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

    private static FgcUserDetails settleUser() {
        return userWithRole("SETTLEMENT");
    }

    private static FgcUserDetails userWithRole(String roleCode) {
        var view = new AppUserView();
        view.setUserId(1L);
        view.setLoginId("tester");
        view.setPasswordHash("x");
        view.setUserName("테스터");
        view.setRoleCode(roleCode);
        return new FgcUserDetails(view, true, true);
    }

    /** 대시보드 화면은 셸까지 렌더링해야 뜬다 — 숫자는 이 테스트들의 관심사가 아니라 전부 0으로 둔다. */
    private void stubEmptySummary() {
        given(dashboardService.summarize(any())).willAnswer(inv -> new DashboardSummaryResult(
                (LocalDate) inv.getArgument(0),
                new DashboardKpiCounts(0, 0, 0, 0, 0, 0),
                List.of(), List.of()));
    }

    @Test
    void month_param_persists_into_next_paramless_request() throws Exception {
        stubEmptySummary();

        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/").param("month", "2026-05").session(session).with(user(settleUser())))
                .andExpect(model().attribute("month", "2026-05"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-value=\"2026-05\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<span id=\"global-month-value\" data-month-selector-value>2026-05</span>")));

        mvc.perform(get("/").session(session).with(user(settleUser())))
                .andExpect(model().attribute("month", "2026-05"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-value=\"2026-05\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<span id=\"global-month-value\" data-month-selector-value>2026-05</span>")));
    }

    /** 기준월의 임시 선택과 확정은 공통 Month Selector와 App Shell adapter가 처리한다. */
    @Test
    void month_selector_is_connected_to_common_shell_scripts() throws Exception {
        stubEmptySummary();

        mvc.perform(get("/").with(user(settleUser())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-month-selector")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "aria-haspopup=\"dialog\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "aria-expanded=\"false\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "src=\"/js/common/month-selector.js\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "src=\"/js/common/app-shell.js\"")));
    }

    /**
     * 사이드바에서 역할 제한이 문서에 명시된 화면은 AUDT-W01 하나뿐이다
     * (화면정의서 v2.0 "권한 COMPLIANCE, SYSTEM_ADMIN"). 이 셸이 21개 화면 전체의 메뉴 기준이라
     * 여기서 새면 나머지 화면에 그대로 전파된다.
     */
    @Test
    void audit_log_menu_is_shown_only_to_compliance_and_system_admin() throws Exception {
        stubEmptySummary();

        for (String allowed : List.of("COMPLIANCE", "SYSTEM_ADMIN")) {
            mvc.perform(get("/").with(user(userWithRole(allowed))))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("/audit-logs")));
        }
        for (String denied : List.of("SETTLEMENT", "GA_ADMIN")) {
            mvc.perform(get("/").with(user(userWithRole(denied))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("/audit-logs"))));
        }
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

        // Ant 패턴 /api/** 는 뒤가 비어 있는 /api 도 잡는다 — SecurityConfig 와 판정이 갈리면 안 된다
        MockHttpServletRequest apiRoot = new MockHttpServletRequest("GET", "/api");
        apiRoot.setSession(session);
        advice.month("2026-02", apiRoot);

        assertThat(session.getAttribute("fgc.month")).isEqualTo("2026-05");

        // context path 가 붙어도 API 는 API 다. getRequestURI() 는 context path 를 포함한다.
        MockHttpServletRequest behindContextPath = new MockHttpServletRequest("GET", "/fgc/api/v1/cap-checks");
        behindContextPath.setContextPath("/fgc");
        behindContextPath.setSession(session);
        advice.month("2026-03", behindContextPath);

        assertThat(session.getAttribute("fgc.month")).isEqualTo("2026-05");

        // 반대로 context path 아래의 화면 요청은 그대로 세션을 바꿔야 한다
        MockHttpServletRequest pageBehindContextPath = new MockHttpServletRequest("GET", "/fgc/");
        pageBehindContextPath.setContextPath("/fgc");
        pageBehindContextPath.setSession(session);
        assertThat(advice.month("2026-04", pageBehindContextPath)).isEqualTo("2026-04");
        assertThat(session.getAttribute("fgc.month")).isEqualTo("2026-04");
        session.setAttribute("fgc.month", "2026-05");

        MockHttpServletRequest nextPage = new MockHttpServletRequest("GET", "/");
        nextPage.setSession(session);
        assertThat(advice.month(null, nextPage)).isEqualTo("2026-05");
    }
}
