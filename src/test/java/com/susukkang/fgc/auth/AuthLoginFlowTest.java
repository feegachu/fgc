package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FUN-001 개발 순서 15 - Test 2

/**
 * FGC-FUN-001 인수조건 검증.
 * "정상 계정은 로그인되고 잘못된 계정은 거절되며 로그아웃 후 보호 URL 접근이 차단된다."
 *
 * 로컬 PostgreSQL(docker-compose fgc-db)의 시드데이터(V3)에 있는 시연 계정을 그대로 쓴다.
 * 트랜잭션은 끝나면 롤백되므로 audit_log 검증 행도 남지 않는다.
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

    private int auditCount(String actionCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fgc.audit_log WHERE action_code = ? AND entity_id = ?",
                Integer.class, actionCode, DEMO_LOGIN_ID);
        return count == null ? 0 : count;
    }

    /** 방금 쌓인 감사 행의 request_id */
    private String latestAuditRequestId(String actionCode) {
        return jdbcTemplate.queryForObject(
                "SELECT request_id FROM fgc.audit_log WHERE action_code = ? AND entity_id = ?"
                        + " ORDER BY audit_log_id DESC LIMIT 1",
                String.class, actionCode, DEMO_LOGIN_ID);
    }

    private static String repeat(char c, int length) {
        return String.valueOf(c).repeat(length);
    }

    /** formLogin() 빌더는 헤더를 받지 못해 X-Request-Id 를 실으려면 직접 POST 해야 한다. */
    private MockHttpServletRequestBuilder loginWithRequestId(String password, String requestId) {
        return post("/login")
                .param("username", DEMO_LOGIN_ID)
                .param("password", password)
                .header("X-Request-Id", requestId)
                .with(csrf());
    }

    // 정상 계정은 로그인되고 "/"로 보낸다
    @Test
    void validCredentialsAuthenticateAndRedirectHome() throws Exception {
        int before = auditCount("LOGIN_SUCCESS");

        mockMvc.perform(formLogin().user(DEMO_LOGIN_ID).password(DEMO_PASSWORD))
                .andExpect(authenticated().withUsername(DEMO_LOGIN_ID).withRoles("SETTLEMENT"))
                .andExpect(redirectedUrl("/"));

        assertThat(auditCount("LOGIN_SUCCESS")).isEqualTo(before + 1);
    }

    // 잘못된 비밀번호는 거절하고 원인을 구분하지 않은 채 /login?error 로만 보낸다
    @Test
    void wrongPasswordIsRejectedAndAudited() throws Exception {
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(formLogin().user(DEMO_LOGIN_ID).password("wrong-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
    }

    // 없는 아이디도 같은 곳으로 보낸다 — 계정 존재 여부가 새어 나가면 안 된다
    @Test
    void unknownLoginIdIsRejectedTheSameWay() throws Exception {
        mockMvc.perform(formLogin().user("no-such-user").password(DEMO_PASSWORD))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    // 미인증 화면 요청은 로그인 화면으로 되돌린다 (MPA = 302)
    @Test
    void anonymousHomeRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost/login"));
    }

    // 미인증 REST 요청은 리다이렉트하지 않고 401 (인터페이스정의서 3-4: Ajax = 401)
    @Test
    void anonymousApiRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/cap/checks"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 폼 로그인 세션으로 /api/** 를 부를 수 있어야 한다.
     * 인터페이스정의서 3-4 가 "화면 스크립트는 401 을 받으면 location='/login'" 이라고 정한 것은
     * 화면이 세션 쿠키로 /api/** 를 Ajax 호출한다는 뜻이다.
     * API 체인에 SessionCreationPolicy.STATELESS 를 걸면 이 테스트가 깨진다 — 그건 설계 위반이다.
     */
    @Test
    void formLoginSessionCanCallApi() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc
                .perform(formLogin().user(DEMO_LOGIN_ID).password(DEMO_PASSWORD))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(get("/api/v1/cap/checks").param("month", "2026-07").session(session))
                .andExpect(status().isOk());
    }

    // X-Request-Id 가 컬럼 상한(80자)과 같은 길이여도 감사 행이 그대로 기록된다
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
    @Test
    void oversizedRequestIdStillLeavesAuditRow() throws Exception {
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(loginWithRequestId("wrong-password", repeat('b', 81)))
                .andExpect(unauthenticated());

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
        assertThat(latestAuditRequestId("LOGIN_FAIL")).hasSizeLessThanOrEqualTo(80);
    }

    // 로그인 화면 자체는 익명 접근을 허용한다
    @Test
    void loginPageIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    // ★ 인수조건: 로그아웃하면 세션을 즉시 지우고, 이후 보호 URL 접근이 차단된다
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
