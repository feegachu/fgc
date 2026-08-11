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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

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
}
