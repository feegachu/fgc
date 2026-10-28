package com.susukkang.fgc.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.security.AuthenticatedSessions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FUN-001 개발 순서 15 - Test 2

/**
 * 설명 : FGC-FUN-001 인수조건 검증.
 * "정상 계정은 로그인되고 잘못된 계정은 거절되며 로그아웃 후 보호 URL 접근이 차단된다."
 *
 * 2026-10-27 yslee - 폼 로그인·세션 기준에서 JWT 토큰 API 기준으로 다시 쓴다(#419 웨이브 C)
 * 감사로그(LOGIN_SUCCESS·LOGIN_FAIL·LOGOUT)와 실패 건수 검증은 그대로 둔다.
 * 토큰 로그인 감사는 커밋 뒤에 쓰므로(AuthAuditListener.recordTokenEvent) @Transactional 을 걸지 않고
 * 끝나면 Refresh 행과 계정 상태를 되돌린다. audit_log 는 INSERT 전용이라 전후 건수 차이로만 본다.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginFlowTest {

    private static final String DEMO_LOGIN_ID = "settle01";
    private static final String DEMO_PASSWORD = "fgc1234!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FgcUserDetailsService userDetailsService;

    @AfterEach
    void restore() {
        jdbcTemplate.update("UPDATE fgc.app_user SET account_status = 'ACTIVE' WHERE login_id = ?", DEMO_LOGIN_ID);
        jdbcTemplate.update("DELETE FROM fgc.auth_refresh_token");
    }

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

    /** 토큰 API 는 Origin·X-FGC-Client 를 요구한다(인터페이스정의서 §2-1-1 "CSRF 판정"). */
    private static MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.ORIGIN, "http://localhost").header("X-FGC-Client", "web");
    }

    private MockHttpServletRequestBuilder login(String loginId, String password) throws Exception {
        return trusted(post("/api/v1/auth/login"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", password)));
    }

    private MockHttpServletResponse loginOk(String loginId) throws Exception {
        return mockMvc.perform(login(loginId, DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private static String bearer(MockHttpServletResponse response) throws Exception {
        return "Bearer " + JsonPath.read(response.getContentAsString(), "$.data.accessToken");
    }

    // 정상 계정은 토큰을 받고 LOGIN_SUCCESS 가 남는다
    @Test
    void validCredentialsIssueTokenAndAreAudited() throws Exception {
        int before = auditCount("LOGIN_SUCCESS");

        MockHttpServletResponse response = loginOk(DEMO_LOGIN_ID);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(response)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value(DEMO_LOGIN_ID))
                .andExpect(jsonPath("$.data.roleCode").value("SETTLEMENT"));
        assertThat(auditCount("LOGIN_SUCCESS")).isEqualTo(before + 1);
    }

    // 잘못된 비밀번호는 거절하고 원인을 구분하지 않는다
    @Test
    void wrongPasswordIsRejectedAndAudited() throws Exception {
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(login(DEMO_LOGIN_ID, "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-001"));

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
    }

    // 없는 아이디도 같은 응답이다 — 계정 존재 여부가 새어 나가면 안 된다
    @Test
    void unknownLoginIdIsRejectedTheSameWay() throws Exception {
        mockMvc.perform(login("no-such-user", DEMO_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCKED", "DISABLED"})
    void inactiveAccountIsRejectedAndAudited(String accountStatus) throws Exception {
        jdbcTemplate.update("UPDATE fgc.app_user SET account_status = ? WHERE login_id = ?",
                accountStatus, DEMO_LOGIN_ID);
        int before = auditCount("LOGIN_FAIL");

        mockMvc.perform(login(DEMO_LOGIN_ID, DEMO_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-001"));

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
    }

    // #419 시나리오 4 — 미인증 요청은 리다이렉트 없이 401 이다(화면 이동은 SPA 가 한다)
    // 2026-10-28 yslee - 응답 모양을 고정한다(PR #734 리뷰): 토큰이 없으면 본문 없는 401, 토큰이 틀리면 JSON 오류 봉투(인터페이스정의서 §5-3).
    @Test
    void anonymousRequestIsUnauthorizedWithoutRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/contracts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().string(""));
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
    }

    // #419 시나리오 5 — STATELESS: 로그인해도 세션 쿠키를 주지 않고, 세션으로는 인증되지 않는다
    // 2026-10-28 yslee - 임의 JSESSIONID 대신 1차 폼 로그인이 남기던 인증 세션(같은 사용자)으로 검사한다(PR #734 리뷰).
    //   세션을 읽는 정책(NEVER 등)으로 되돌아가면 이 요청이 200 이 되어 실패한다.
    @Test
    void sessionCookieNeitherIssuedNorAccepted() throws Exception {
        MockHttpServletResponse response = loginOk(DEMO_LOGIN_ID);
        assertThat(response.getCookie("JSESSIONID")).isNull();

        FgcUserDetails user = (FgcUserDetails) userDetailsService.loadUserByUsername(DEMO_LOGIN_ID);
        MockHttpSession authenticatedSession = AuthenticatedSessions.of(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        mockMvc.perform(get("/api/v1/contracts").session(authenticatedSession))
                .andExpect(status().isUnauthorized());
    }

    // 1차 폼 로그인(POST /login)은 더 이상 로그인 경로가 아니다
    @Test
    void legacyFormLoginEndpointDoesNotAuthenticate() throws Exception {
        mockMvc.perform(post("/login").param("username", DEMO_LOGIN_ID).param("password", DEMO_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    // X-Request-Id 가 컬럼 상한(80자)과 같은 길이여도 감사 행이 그대로 기록된다
    @Test
    void requestIdAtColumnLimitIsAudited() throws Exception {
        String requestId = "a".repeat(80);
        int before = auditCount("LOGIN_SUCCESS");

        mockMvc.perform(login(DEMO_LOGIN_ID, DEMO_PASSWORD).header("X-Request-Id", requestId))
                .andExpect(status().isOk());

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

        mockMvc.perform(login(DEMO_LOGIN_ID, "wrong-password").header("X-Request-Id", "b".repeat(81)))
                .andExpect(status().isUnauthorized());

        assertThat(auditCount("LOGIN_FAIL")).isEqualTo(before + 1);
        assertThat(latestAuditRequestId("LOGIN_FAIL")).hasSizeLessThanOrEqualTo(80);
    }

    /**
     * FUN-002(#82) — POST /** 굵은 규칙은 COMPLIANCE 를 배제하지만 로그아웃은 막히지 않아야 한다.
     * PR #145 리뷰 요구사항 1(COMPLIANCE 계정 로그아웃 확인)의 자동화.
     */
    @Test
    void complianceUserCanStillLogoutDespiteBulkPostRule() throws Exception {
        Cookie refresh = loginOk("audit01").getCookie("fgc_refresh");

        mockMvc.perform(trusted(post("/api/v1/auth/logout")).cookie(refresh))
                .andExpect(status().isOk());
    }

    // ★ 인수조건: 로그아웃하면 로그인이 즉시 끝나고, 이후 보호 URL 접근이 차단된다
    @Test
    void logoutEndsLoginAndBlocksProtectedUrl() throws Exception {
        MockHttpServletResponse response = loginOk(DEMO_LOGIN_ID);
        String bearer = bearer(response);
        int before = auditCount("LOGOUT");

        mockMvc.perform(trusted(post("/api/v1/auth/logout")).cookie(response.getCookie("fgc_refresh")))
                .andExpect(status().isOk());

        assertThat(auditCount("LOGOUT")).isEqualTo(before + 1);
        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
    }
}
