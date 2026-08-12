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

    /**
     * COMPLIANCE "모든 처리 버튼 회색"(화면정의서 :229) — readOnly 모델값이 아니라
     * 실제 렌더링(th:disabled)을 본다. 대표로 RECO-W01 의 처리 버튼 2개.
     */
    @Test
    void process_buttons_disabled_for_compliance_but_not_settlement() throws Exception {
        mvc.perform(get("/reconciliations").with(user(complianceUser())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("disabled=\"disabled\"")));
        mvc.perform(get("/reconciliations").with(user(settleUser())))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("disabled=\"disabled\""))));
    }

    /** 교육용 면책문구(COR-009)는 어느 화면에서도 빠지면 안 된다 — 대표로 한 화면만 확인. */
    @Test
    void footer_disclaimer_present() throws Exception {
        mvc.perform(get("/cap-checks").with(user(settleUser())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "교육용 프로토타입입니다. 실제 지급 결정에 사용할 수 없습니다.")));
    }

    private static com.susukkang.fgc.auth.dto.FgcUserDetails gaAdminUser() {
        var view = new com.susukkang.fgc.auth.dto.AppUserView();
        view.setUserId(3L);
        view.setLoginId("gaadmin");
        view.setPasswordHash("x");
        view.setUserName("GA관리");
        view.setRoleCode("GA_ADMIN");
        return new com.susukkang.fgc.auth.dto.FgcUserDetails(view, true, true);
    }

    private static com.susukkang.fgc.auth.dto.FgcUserDetails adminUser() {
        var view = new com.susukkang.fgc.auth.dto.AppUserView();
        view.setUserId(4L);
        view.setLoginId("admin");
        view.setPasswordHash("x");
        view.setUserName("시스템관리");
        view.setRoleCode("SYSTEM_ADMIN");
        return new com.susukkang.fgc.auth.dto.FgcUserDetails(view, true, true);
    }

    private static com.susukkang.fgc.auth.dto.FgcUserDetails userFor(String role) {
        return switch (role) {
            case "SETTLEMENT" -> settleUser();
            case "GA_ADMIN" -> gaAdminUser();
            case "SYSTEM_ADMIN" -> adminUser();
            case "COMPLIANCE" -> complianceUser();
            default -> throw new IllegalArgumentException(role);
        };
    }

    /**
     * FUN-002 인수조건: 권한 없는 역할의 직접 URL 호출은 403 으로 차단된다.
     * 폼 라우트는 SETTLEMENT·SYSTEM_ADMIN 전용 — 인터페이스정의서 IF-API-18/19/22,
     * 화면정의서 :590·:686 (GA_ADMIN 은 2026-08-12 교차 판정으로 제외, 근거대장 참조).
     */
    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "SETTLEMENT,   /contracts/new,    200",
            "SYSTEM_ADMIN, /contracts/new,    200",
            "GA_ADMIN,     /contracts/new,    403",
            "COMPLIANCE,   /contracts/new,    403",
            "GA_ADMIN,     /contracts/1/edit, 403",
            "COMPLIANCE,   /contracts/1/edit, 403",
            "SETTLEMENT,   /transactions/new, 200",
            "SYSTEM_ADMIN, /transactions/new, 200",
            "GA_ADMIN,     /transactions/new, 403",
            "COMPLIANCE,   /transactions/new, 403",
    })
    void form_route_access_by_role(String role, String route, int expected) throws Exception {
        mvc.perform(get(route).with(user(userFor(role))))
                .andExpect(status().is(expected));
    }

    /**
     * 등록 버튼(a 태그)은 disabled 가 안 먹혀 숨긴다 — canProcess(th:if).
     * CONT-W01 :531 / TRAN-W01 :673. 앵커에만 있는 data-fgc-action="create" 로 판별한다.
     */
    @ParameterizedTest(name = "{0} {1} 등록버튼 노출={2}")
    @CsvSource({
            "SETTLEMENT,   /contracts,    true",
            "SYSTEM_ADMIN, /contracts,    true",
            "GA_ADMIN,     /contracts,    false",
            "COMPLIANCE,   /contracts,    false",
            "SETTLEMENT,   /transactions, true",
            "GA_ADMIN,     /transactions, false",
    })
    void register_anchor_shown_only_for_processing_roles(String role, String route, boolean visible) throws Exception {
        var marker = org.hamcrest.Matchers.containsString("data-fgc-action=\"create\"");
        mvc.perform(get(route).with(user(userFor(role))))
                .andExpect(content().string(visible ? marker : org.hamcrest.Matchers.not(marker)));
    }

    /**
     * GA_ADMIN 은 readOnly=false 지만 등록·실행은 못 한다(§4-1 "정책·조직 조회, 검증 실행 확정").
     * readOnly 만 보던 시절 GA_ADMIN 에게 처리 버튼이 활성이던 회귀를 막는다 — 대표로
     * SCHE-W02(재생성·확정, :873)와 CONT-W02(처리 버튼, IF-API-19/29/33).
     */
    @ParameterizedTest(name = "{0} 처리버튼 비활성")
    @CsvSource({"/schedules/1", "/contracts/1"})
    void process_buttons_disabled_for_ga_admin(String route) throws Exception {
        mvc.perform(get(route).with(user(gaAdminUser())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("disabled=\"disabled\"")));
        mvc.perform(get(route).with(user(adminUser())))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("disabled=\"disabled\""))));
    }
}
