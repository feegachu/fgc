package com.susukkang.fgc.common.web;

import com.susukkang.fgc.common.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 정적 부착 화면 18개 라우트 렌더링 스모크 — 라우트가 200 을 주고
 * 셸(page 프래그먼트)이 해당 화면 ID 를 헤더에 찍는지만 본다.
 * 실데이터 바인딩 검증은 화면별 기능 브랜치의 몫이다.
 *
 * 요구사항 추적(FGC-FUN-xxx): BASE 005~009 · POL 011~013 · CONT 018 ·
 * TRAN 065/031/033/034 · SCHE 036/039 · CAP 030/032/035 · ARB 063 ·
 * LEDG 046/047 · RECO 048~051 · EXCP 052/053 · VRUN 041~044 · AUDT 061.
 * /audit-logs 만 역할 제한(FUN-002·화면정의서 :1491)이라 별도 테스트로 뺐다.
 */
@WebMvcTest(ScreenViewController.class)
@Import({ShellAdvice.class, SecurityConfig.class, MessageSourceAutoConfiguration.class,
        com.susukkang.fgc.common.exception.FgcMessageResolver.class,
        com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver.class})
@TestPropertySource(properties = "fgc.demo-month=2026-07")
class ScreenViewControllerTest {

    @Autowired
    MockMvc mvc;

    private static com.susukkang.fgc.auth.dto.FgcUserDetails settleUser() {
        var view = new com.susukkang.fgc.auth.dto.AppUserView();
        view.setUserId(1L);
        view.setLoginId("settle01");
        view.setPasswordHash("x");
        view.setUserName("정산담당");
        view.setRoleCode("SETTLEMENT");
        return new com.susukkang.fgc.auth.dto.FgcUserDetails(view, true, true);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "/base,                FGC-UI-BASE-W01",
            "/policies,            FGC-UI-POL-W01",
            "/contracts,           FGC-UI-CONT-W01",
            "/contracts/1,         FGC-UI-CONT-W02",
            "/contracts/new,       FGC-UI-CONT-W03",
            "/contracts/1/edit,    FGC-UI-CONT-W03",
            "/transactions,        FGC-UI-TRAN-W01",
            "/transactions/new,    FGC-UI-TRAN-W02",
            "/schedules,           FGC-UI-SCHE-W01",
            "/schedules/1,         FGC-UI-SCHE-W02",
            "/cap-checks,          FGC-UI-CAP-W01",
            "/arbitrage-checks,    FGC-UI-ARB-W01",
            "/journals,            FGC-UI-LEDG-W01",
            "/reconciliations,     FGC-UI-RECO-W01",
            "/exceptions,          FGC-UI-EXCP-W01",
            "/validation-runs,     FGC-UI-VRUN-W01",
            "/validation-runs/1,   FGC-UI-VRUN-W02",
    })
    void screen_renders_with_its_id(String route, String screenId) throws Exception {
        mvc.perform(get(route).with(user(settleUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(screenId)));
    }

    private static com.susukkang.fgc.auth.dto.FgcUserDetails complianceUser() {
        var view = new com.susukkang.fgc.auth.dto.AppUserView();
        view.setUserId(2L);
        view.setLoginId("comp01");
        view.setPasswordHash("x");
        view.setUserName("준법감사");
        view.setRoleCode("COMPLIANCE");
        return new com.susukkang.fgc.auth.dto.FgcUserDetails(view, true, true);
    }

    /** AUDT-W01(FUN-061)은 COMPLIANCE·SYSTEM_ADMIN 전용 — 화면정의서 :1491. */
    @Test
    void audit_log_screen_renders_for_compliance() throws Exception {
        mvc.perform(get("/audit-logs").with(user(complianceUser())))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FGC-UI-AUDT-W01")));
    }

    /** FUN-002 인수조건: 권한 없는 역할의 직접 URL 호출은 403 으로 차단된다. */
    @Test
    void audit_log_screen_forbidden_for_settlement() throws Exception {
        mvc.perform(get("/audit-logs").with(user(settleUser())))
                .andExpect(status().isForbidden());
    }

    /** 교육용 면책문구(COR-009)는 어느 화면에서도 빠지면 안 된다 — 대표로 한 화면만 확인. */
    @Test
    void footer_disclaimer_present() throws Exception {
        mvc.perform(get("/cap-checks").with(user(settleUser())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "교육용 프로토타입입니다. 실제 지급 결정에 사용할 수 없습니다.")));
    }
}
