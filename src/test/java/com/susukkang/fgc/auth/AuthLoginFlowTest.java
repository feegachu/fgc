package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FUN-001 개발 순서 15 - Test 2

/**
 * 설명 : FGC-FUN-001 인수조건 검증.
 * "정상 계정은 로그인되고 잘못된 계정은 거절되며 로그아웃 후 보호 URL 접근이 차단된다."
 *
 * 로컬 PostgreSQL(docker-compose fgc-db)의 시드데이터(V3)에 있는 시연 계정을 그대로 쓴다.
 * 트랜잭션은 끝나면 롤백되므로 audit_log 검증 행도 남지 않는다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthLoginFlowTest {

    private static final String DEMO_LOGIN_ID = "settle01";
    private static final String DEMO_PASSWORD = "fgc1234!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 설명 : 시연 계정에 기록된 지정 작업 유형의 감사로그 건수를 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private int auditCount(String actionCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fgc.audit_log WHERE action_code = ? AND entity_id = ?",
                Integer.class, actionCode, DEMO_LOGIN_ID);
        return count == null ? 0 : count;
    }

    /**
     * 설명 : 방금 쌓인 감사 행의 request_id
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private String latestAuditRequestId(String actionCode) {
        return jdbcTemplate.queryForObject(
                "SELECT request_id FROM fgc.audit_log WHERE action_code = ? AND entity_id = ?"
                        + " ORDER BY audit_log_id DESC LIMIT 1",
                String.class, actionCode, DEMO_LOGIN_ID);
    }

    /**
     * 설명 : 지정한 문자를 요청한 길이만큼 반복한 테스트 문자열을 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private static String repeat(char c, int length) {
        return String.valueOf(c).repeat(length);
    }

    /**
     * 설명 : formLogin() 빌더는 헤더를 받지 못해 X-Request-Id 를 실으려면 직접 POST 해야 한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private MockHttpServletRequestBuilder loginWithRequestId(String password, String requestId) {
        return post("/login")
                .param("username", DEMO_LOGIN_ID)
                .param("password", password)
                .header("X-Request-Id", requestId)
                .with(csrf());
    }

    // 정상 계정은 로그인되고 "/"로 보낸다
    /**
     * 설명 : 정상 계정의 인증과 역할 부여 및 홈 이동과 로그인 성공 감사를 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void validCredentialsAuthenticateAndRedirectHome() throws Exception {
        int before = auditCount("LOGIN_SUCCESS");

        mockMvc.perform(formLogin().user(DEMO_LOGIN_ID).password(DEMO_PASSWORD))
                .andExpect(authenticated().withUsername(DEMO_LOGIN_ID).withRoles("SETTLEMENT"))
                .andExpect(redirectedUrl("/"));

        assertThat(auditCount("LOGIN_SUCCESS")).isEqualTo(before + 1);
    }

    // 잘못된 비밀번호는 거절하고 원인을 구분하지 않은 채 /login?error 로만 보낸다
    /**
     * 설명 : 잘못된 비밀번호의 인증 거절과 기존 실패 이동 경로 및 감사 기록을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void wrongPasswordIsRejectedAndAudited() throws Exception {
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(formLogin().user(DEMO_LOGIN_ID).password("wrong-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
    }

    // 없는 아이디도 같은 곳으로 보낸다 — 계정 존재 여부가 새어 나가면 안 된다
    /**
     * 설명 : 존재하지 않는 사용자도 기존 로그인 실패 경로로 처리되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void unknownLoginIdIsRejectedTheSameWay() throws Exception {
        mockMvc.perform(formLogin().user("no-such-user").password(DEMO_PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    /**
     * 설명 : 잠금 및 비활성 계정의 로그인 거절과 실패 감사 기록을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED"})
    void inactiveAccountIsRejectedAndAudited(String accountStatus) throws Exception {
        jdbcTemplate.update("UPDATE fgc.app_user SET account_status = ? WHERE login_id = ?",
                accountStatus, DEMO_LOGIN_ID);
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(formLogin().user(DEMO_LOGIN_ID).password(DEMO_PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
    }

    // 미인증 화면 요청은 로그인 화면으로 되돌린다 (MPA = 302)
    /**
     * 설명 : 미인증 사용자의 홈 요청이 로그인 화면으로 이동하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void anonymousHomeRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost/login"));
    }

    // 미인증 REST 요청은 리다이렉트하지 않고 401 (인터페이스정의서 3-4: Ajax = 401)
    /**
     * 설명 : 미인증 API 요청에 HTTP 401 상태가 반환되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void anonymousApiRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/cap-checks"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 폼 로그인 세션으로 /api/** 를 부를 수 있어야 한다.
     * 인터페이스정의서 3-4 가 "화면 스크립트는 401 을 받으면 location='/login'" 이라고 정한 것은
     * 화면이 세션 쿠키로 /api/** 를 Ajax 호출한다는 뜻이다.
     * API 체인에 SessionCreationPolicy.STATELESS 를 걸면 이 테스트가 깨진다 — 그건 설계 위반이다.
     */
    /**
     * 설명 : 폼 로그인으로 생성한 세션으로 보호된 API를 호출할 수 있는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void formLoginSessionCanCallApi() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc
                .perform(formLogin().user(DEMO_LOGIN_ID).password(DEMO_PASSWORD))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(get("/api/v1/cap-checks").param("month", "2026-07").session(session))
                .andExpect(status().isOk());
    }

    // X-Request-Id 가 컬럼 상한(80자)과 같은 길이여도 감사 행이 그대로 기록된다
    /**
     * 설명 : 요청 식별자가 컬럼 최대 길이일 때 값과 감사 기록이 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void requestIdAtColumnLimitIsAudited() throws Exception {
        String requestId = repeat('a', 80);
        int before = auditCount("LOGIN_SUCCESS");

        mockMvc.perform(loginWithRequestId(DEMO_PASSWORD, requestId))
                .andExpect(authenticated());

        assertThat(auditCount("LOGIN_SUCCESS")).isEqualTo(before + 1);
        assertThat(latestAuditRequestId("LOGIN_SUCCESS")).isEqualTo(requestId);
    }

    /*
     * 81자짜리 X-Request-Id 를 보내도 감사 행을 잃지 않는다.
     * request_id 는 varchar(80) 이라 그대로 넣으면 INSERT 가 통째로 실패하고, 그 실패는
     * ERROR 로그로만 남아 감사 행이 조용히 사라진다. 로그인 무차별 대입을 하는 쪽이
     * 긴 헤더를 실어 자기 LOGIN_FAIL 기록을 지울 수 있으므로 반드시 막아야 한다.
     */
    /**
     * 설명 : 요청 식별자가 컬럼 길이를 초과해도 길이를 보정해 감사 기록을 남기는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void oversizedRequestIdStillLeavesAuditRow() throws Exception {
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(loginWithRequestId("wrong-password", repeat('b', 81)))
                .andExpect(unauthenticated());

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
        assertThat(latestAuditRequestId("LOGIN_FAIL")).hasSizeLessThanOrEqualTo(80);
    }

    // 로그인 화면 자체는 익명 접근을 허용한다
    /**
     * 설명 : 로그인 화면과 인증 화면 스타일 파일에 익명 접근이 가능한지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void loginPageIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC WORKSPACE")))
                .andExpect(content().string(containsString("/css/features/auth.css")));
        mockMvc.perform(get("/css/features/auth.css")).andExpect(status().isOk());
    }

    /**
     * 설명 : FUN-002(#82) — SecurityConfig 의 POST /** 굵은 규칙은 COMPLIANCE 를 배제하지만,
     * POST /logout 은 LogoutFilter 가 AuthorizationFilter 앞단이라 로그아웃은 막히지 않아야 한다.
     * PR #145 리뷰 요구사항 1(COMPLIANCE 계정 로그아웃 확인)의 자동화.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void complianceUserCanStillLogoutDespiteBulkPostRule() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc
                .perform(formLogin().user("audit01").password(DEMO_PASSWORD))
                .andExpect(authenticated().withUsername("audit01").withRoles("COMPLIANCE"))
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?logout"));
    }

    // ★ 인수조건: 로그아웃하면 세션을 즉시 지우고, 이후 보호 URL 접근이 차단된다
    /**
     * 설명 : 로그아웃 감사와 세션 무효화 및 이후 보호 화면 접근 차단을 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void logoutClearsSessionAndBlocksProtectedUrl() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc
                .perform(formLogin().user(DEMO_LOGIN_ID).password(DEMO_PASSWORD))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);

        int before = auditCount("LOGOUT");

        // FUN-001 개발 순서 16 - Test 2 - Update 1
        // LogoutRequestBuilder 는 세션을 받지 못해 직접 POST 한다(CSRF 켜져 있음)
        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?logout"));

        assertThat(auditCount("LOGOUT")).isEqualTo(before + 1);
        assertThat(session.isInvalid()).isTrue();

        mockMvc.perform(get("/").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost/login"));
    }
}
