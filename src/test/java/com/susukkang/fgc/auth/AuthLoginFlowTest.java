package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
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
        mockMvc.perform(get("/api/cap/checks"))
                .andExpect(status().isUnauthorized());
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
