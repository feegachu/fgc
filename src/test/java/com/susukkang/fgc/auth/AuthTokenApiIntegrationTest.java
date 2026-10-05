package com.susukkang.fgc.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : #400 JWT 공존 모드와 토큰 API 검증 시나리오 1~10(이슈 본문 표 번호).
 * 시나리오 11(기존 AuthLoginFlowTest·감사로그 테스트)은 해당 테스트가 그대로 지킨다.
 *
 * 로그인·회전·동시성은 커밋된 상태를 봐야 해서 @Transactional 을 걸지 않고, 끝나면 auth_refresh_token 을 비운다.
 * audit_log 는 INSERT 전용 트리거라 지울 수 없으므로 감사 검증은 전후 건수 차이로만 본다.
 * 지급 등록 서비스만 목으로 바꾼다 — 시나리오 2는 보안 계층(Bearer + CSRF 면제)이 컨트롤러까지 통과시키는지가 대상이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthTokenApiIntegrationTest {

    private static final String PASSWORD = "fgc1234!";
    private static final String TRUSTED_ORIGIN = "http://localhost";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private AuthTokenService authTokenService;
    @Autowired
    private FgcUserDetailsService userDetailsService;
    @MockitoBean
    private CommissionPaymentService commissionPaymentService;

    private record Tokens(String access, String refresh) {
    }

    @AfterEach
    void clearRefreshTokens() {
        jdbcTemplate.update("DELETE FROM fgc.auth_refresh_token");
    }

    // ── 시나리오 1·2: CI smoke ─────────────────────────────────────────────

    @Test
    void smokeSessionLoginCanStillGetContracts() throws Exception {
        MockHttpSession session = sessionLogin("settle01");

        mockMvc.perform(get("/api/v1/contracts").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void smokeBearerPostTransactionNeedsNoCsrfToken() throws Exception {
        Tokens tokens = login("settle01");

        mockMvc.perform(post("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentJson()))
                .andExpect(status().isCreated());
    }

    // ── 시나리오 3: login → me → refresh → 새 access ─────────────────────────

    @Test
    void loginMeRefreshAndCallWithNewAccessToken() throws Exception {
        int successBefore = auditCount("LOGIN_SUCCESS", "settle01");
        MockHttpServletResponse loginResponse = mockMvc.perform(trusted(post("/api/v1/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("settle01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn().getResponse();
        String setCookie = loginResponse.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("fgc_refresh=", "HttpOnly", "Secure", "SameSite=Strict",
                "Path=/api/v1/auth", "Max-Age=1209600");
        assertThat(auditCount("LOGIN_SUCCESS", "settle01")).isEqualTo(successBefore + 1);

        Tokens tokens = tokensOf(loginResponse);
        // DB 에는 원문이 아니라 해시만 남는다.
        assertThat(count("SELECT count(*) FROM fgc.auth_refresh_token WHERE token_hash = ?", tokens.refresh()))
                .isZero();
        assertThat(count("SELECT count(*) FROM fgc.auth_refresh_token WHERE token_hash = ?", sha256(tokens.refresh())))
                .isOne();

        // 플래그는 역할 조합(Roles)과 같다 — SETTLEMENT: 처리·예외·역분개 O / 감사로그·검증확정 X.
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginId").value("settle01"))
                .andExpect(jsonPath("$.data.userName").value("정산담당자"))
                .andExpect(jsonPath("$.data.roleCode").value("SETTLEMENT"))
                .andExpect(jsonPath("$.data.canProcess").value(true))
                .andExpect(jsonPath("$.data.canViewAuditLog").value(false))
                .andExpect(jsonPath("$.data.canHandleException").value(true))
                .andExpect(jsonPath("$.data.canReverseJournal").value(true))
                .andExpect(jsonPath("$.data.canFinalizeValidation").value(false));

        Tokens refreshed = refresh(tokens.refresh());
        assertThat(refreshed.refresh()).isNotEqualTo(tokens.refresh());
        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshed.access()))
                .andExpect(status().isOk());
    }

    @Test
    void meReturnsAuditFlagsForComplianceAndWorksWithSession() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").session(sessionLogin("audit01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("COMPLIANCE"))
                .andExpect(jsonPath("$.data.canProcess").value(false))
                .andExpect(jsonPath("$.data.canViewAuditLog").value(true))
                .andExpect(jsonPath("$.data.canFinalizeValidation").value(false));
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingReasonAndAudited() throws Exception {
        int failBefore = auditCount("LOGIN_FAIL", "settle01");

        mockMvc.perform(trusted(post("/api/v1/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", "settle01", "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-001"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        mockMvc.perform(trusted(post("/api/v1/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("loginId", "nobody", "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-001"));

        assertThat(auditCount("LOGIN_FAIL", "settle01")).isEqualTo(failBefore + 1);
        assertThat(count("SELECT count(*) FROM fgc.auth_refresh_token")).isZero();
    }

    // ── 시나리오 4: 만료·서명 위조·다른 키 ───────────────────────────────────

    @Test
    void expiredForgedOrForeignKeyTokensAreRejectedWithEnvelope() throws Exception {
        Tokens tokens = login("settle01");
        String sid = claim(tokens.access(), "sid");
        Instant now = Instant.now();

        String expired = sign(jwtEncoder, claims("settle01", sid, now.minusSeconds(3600), now.minusSeconds(600)));
        String tampered = tokens.access().substring(0, tokens.access().length() - 4) + "AAAA";
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "another-key-another-key-another-key!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String foreign = sign(foreignEncoder, claims("settle01", sid, now, now.plusSeconds(600)));

        int failBefore = auditCount("LOGIN_FAIL", null);
        for (String bad : List.of(expired, tampered, foreign, "not-a-jwt")) {
            mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"))
                    .andExpect(jsonPath("$.requestId").exists());
        }
        // 토큰 인증 실패는 로그인 실패 감사로 남기지 않는다(토큰 원문이 entity_id 에 적히지 않게).
        assertThat(auditCount("LOGIN_FAIL", null)).isEqualTo(failBefore);
    }

    // ── 시나리오 5: COMPLIANCE 쓰기, 클레임 역할 조작 ─────────────────────────

    @Test
    void complianceTokenCannotWriteEvenWithEscalatedRolesClaim() throws Exception {
        Tokens tokens = login("audit01");

        mockMvc.perform(post("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"));

        // 키가 새어 roles 클레임을 SYSTEM_ADMIN 으로 바꿔 서명했다고 가정해도 역할은 DB 에서 다시 읽는다.
        Instant now = Instant.now();
        JwtClaimsSet escalated = JwtClaimsSet.builder()
                .subject("audit01").issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("sid", claim(tokens.access(), "sid"))
                .claim("roles", List.of("SYSTEM_ADMIN"))
                .build();
        mockMvc.perform(post("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(jwtEncoder, escalated))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentJson()))
                .andExpect(status().isForbidden());
    }

    // ── 시나리오 6: 로그아웃 후 재사용, 회전 전 Refresh 재사용 ─────────────────

    @Test
    void refreshTokenIsUselessAfterLogoutAndAccessIsCutImmediately() throws Exception {
        Tokens tokens = login("settle01");
        int logoutBefore = auditCount("LOGOUT", "settle01");

        mockMvc.perform(trusted(post("/api/v1/auth/logout")).cookie(refreshCookie(tokens.refresh())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertThat(auditCount("LOGOUT", "settle01")).isEqualTo(logoutBefore + 1);

        mockMvc.perform(trusted(post("/api/v1/auth/refresh")).cookie(refreshCookie(tokens.refresh())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.access()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-002"));
    }

    @Test
    void reusingRotatedRefreshRevokesEveryLoginOfThatUser() throws Exception {
        Tokens first = login("settle01");
        Tokens second = refresh(first.refresh());
        int reuseBefore = auditCount("REFRESH_REUSE_DETECTED", "settle01");

        mockMvc.perform(trusted(post("/api/v1/auth/refresh")).cookie(refreshCookie(first.refresh())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));

        // 탈취로 보고 정상 사용자의 최신 Refresh 까지 끊는다 — 무효화는 401 응답과 함께 커밋돼야 한다.
        assertThat(auditCount("REFRESH_REUSE_DETECTED", "settle01")).isEqualTo(reuseBefore + 1);
        assertThat(count("SELECT count(*) FROM fgc.auth_refresh_token WHERE revoked_at IS NULL")).isZero();
        mockMvc.perform(trusted(post("/api/v1/auth/refresh")).cookie(refreshCookie(second.refresh())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + second.access()))
                .andExpect(status().isUnauthorized());
    }

    // ── 시나리오 7: 세션 요청은 CSRF 유지 ─────────────────────────────────────

    @Test
    void sessionPostWithoutCsrfTokenIsStillForbidden() throws Exception {
        MockHttpSession session = sessionLogin("settle01");

        mockMvc.perform(post("/api/v1/transactions").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentJson()))
                .andExpect(status().isForbidden());
        // 가짜 Bearer 헤더를 붙여 CSRF 를 피하려 해도 세션으로 넘어가지 않고 토큰 인증에서 거절된다.
        mockMvc.perform(post("/api/v1/transactions").session(session)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPaymentJson()))
                .andExpect(status().isUnauthorized());
    }

    // ── 시나리오 8: X-FGC-Client·Origin ─────────────────────────────────────

    @Test
    void tokenEndpointsRejectMissingClientHeaderOrForeignOrigin() throws Exception {
        Tokens tokens = login("settle01");
        Cookie cookie = refreshCookie(tokens.refresh());

        mockMvc.perform(post("/api/v1/auth/refresh").header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        for (String origin : List.of("https://evil.example", "null", "http://localhost:5173")) {
            mockMvc.perform(post("/api/v1/auth/refresh").header("X-FGC-Client", "web")
                            .header(HttpHeaders.ORIGIN, origin).cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
        mockMvc.perform(post("/api/v1/auth/refresh").header("X-FGC-Client", "web").cookie(cookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/login").header("X-FGC-Client", "web")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("settle01")))
                .andExpect(status().isForbidden());

        // 거절된 요청은 아무것도 회전시키지 않았다 — 원래 Refresh 가 그대로 살아 있다.
        refresh(tokens.refresh());
    }

    /**
     * 운영은 nginx 가 TLS 를 끝내고 Tomcat RemoteIpValve(Containerfile.api)가 요청을 https:443 으로 되돌린다.
     * 그 상태에서 브라우저 Origin(https://도메인, 기본 포트 생략)과 일치해야 하고, http Origin 은 거절해야 한다.
     */
    @Test
    void originCheckFollowsHttpsBehindTlsTerminatingProxy() throws Exception {
        org.springframework.test.web.servlet.request.RequestPostProcessor behindTls = request -> {
            request.setScheme("https");
            request.setSecure(true);
            request.setServerName("feegachu.cloud");
            request.setServerPort(443);
            return request;
        };

        mockMvc.perform(post("/api/v1/auth/login").with(behindTls)
                        .header(HttpHeaders.ORIGIN, "https://feegachu.cloud").header("X-FGC-Client", "web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("settle01")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/login").with(behindTls)
                        .header(HttpHeaders.ORIGIN, "http://feegachu.cloud").header("X-FGC-Client", "web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("settle01")))
                .andExpect(status().isForbidden());
    }

    // ── 시나리오 9: 중복 로그인 차단 ─────────────────────────────────────────

    @Test
    void secondLoginKicksOutFirstWithAuth004() throws Exception {
        Tokens browserA = login("settle01");
        int supersededBefore = auditCount("LOGIN_SUPERSEDED", "settle01");
        Tokens browserB = login("settle01");
        assertThat(auditCount("LOGIN_SUPERSEDED", "settle01")).isEqualTo(supersededBefore + 1);

        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + browserA.access()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-004"))
                .andExpect(jsonPath("$.error.message")
                        .value("다른 곳에서 같은 계정으로 로그인해 로그아웃되었습니다. 다시 로그인하세요."));
        mockMvc.perform(trusted(post("/api/v1/auth/refresh")).cookie(refreshCookie(browserA.refresh())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-004"));

        // A 의 갱신 시도가 B 를 끊지 않는다(재사용 탐지 대상 아님).
        mockMvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, "Bearer " + browserB.access()))
                .andExpect(status().isOk());
        refresh(browserB.refresh());
    }

    // ── 시나리오 10: 동시 로그인 ─────────────────────────────────────────────

    @Test
    void concurrentLoginsLeaveExactlyOneActiveSession() throws Exception {
        FgcUserDetails user = (FgcUserDetails) userDetailsService.loadUserByUsername("settle01");
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Callable<AuthTokenService.IssuedTokens> task = () -> {
                start.await();
                return authTokenService.login(user);
            };
            List<Future<AuthTokenService.IssuedTokens>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(task));
            }
            start.countDown();
            for (Future<AuthTokenService.IssuedTokens> result : results) {
                assertThat(result.get().accessToken()).isNotBlank();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM fgc.auth_refresh_token WHERE revoked_at IS NULL")).isOne();
        assertThat(count("SELECT count(*) FROM fgc.auth_refresh_token WHERE revoked_reason = 'SUPERSEDED'"))
                .isEqualTo(threads - 1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN).header("X-FGC-Client", "web");
    }

    private Tokens login(String loginId) throws Exception {
        return tokensOf(mockMvc.perform(trusted(post("/api/v1/auth/login"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(loginId)))
                .andExpect(status().isOk())
                .andReturn().getResponse());
    }

    private Tokens refresh(String refreshToken) throws Exception {
        return tokensOf(mockMvc.perform(trusted(post("/api/v1/auth/refresh")).cookie(refreshCookie(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse());
    }

    private static Tokens tokensOf(MockHttpServletResponse response) throws Exception {
        String access = JsonPath.read(response.getContentAsString(), "$.data.accessToken");
        return new Tokens(access, response.getCookie("fgc_refresh").getValue());
    }

    private MockHttpSession sessionLogin(String loginId) throws Exception {
        return (MockHttpSession) mockMvc.perform(formLogin().user(loginId).password(PASSWORD))
                .andReturn().getRequest().getSession(false);
    }

    private static Cookie refreshCookie(String value) {
        return new Cookie("fgc_refresh", value);
    }

    private String loginJson(String loginId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("loginId", loginId, "password", PASSWORD));
    }

    private String claim(String token, String name) throws Exception {
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$." + name);
    }

    private static JwtClaimsSet claims(String subject, String sid, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder().subject(subject).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("sid", sid).claim("roles", List.of("SETTLEMENT")).build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private int auditCount(String actionCode, String loginId) {
        Integer value = loginId == null
                ? jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.audit_log WHERE action_code = ?",
                Integer.class, actionCode)
                : jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fgc.audit_log WHERE action_code = ? AND entity_id = ?",
                Integer.class, actionCode, loginId);
        return value == null ? 0 : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String sha256(String raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private String validPaymentJson() throws Exception {
        return objectMapper.writeValueAsString(Map.ofEntries(
                Map.entry("sourceType", "GA_MANUAL_PAYMENT"),
                Map.entry("sourceBusinessKey", "GA-2026-07-0001"),
                Map.entry("contractId", 3L),
                Map.entry("agentId", 7L),
                Map.entry("commissionItemId", 11L),
                Map.entry("amount", 500000),
                Map.entry("settlementMonth", "2026-07-01"),
                Map.entry("cashflowType", "PAYMENT"),
                Map.entry("scheduledPaymentDate", "2026-07-25"),
                Map.entry("paymentStage", "GA_TO_FC"),
                Map.entry("allocationPolicyVersion", 3L),
                Map.entry("evidenceRef", "PAYMENT-EVIDENCE"),
                Map.entry("attributions", List.of(Map.of(
                        "contractId", 3L,
                        "attributionDate", "2026-07-10",
                        "amount", 500000,
                        "inclusionDecisionStatus", "INCLUDED",
                        "exclusionType", "NONE",
                        "inclusionDecisionReason", "룰셋 산입",
                        "allocationBasis", "DIRECT",
                        "evidenceRef", "EVIDENCE-001",
                        "attributionMethod", "DIRECT")))));
    }
}
