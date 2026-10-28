package com.susukkang.fgc.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : 감사로그 저장 실패 시에도 기존 로그인 흐름이 유지되는지 검증하는 통합 테스트.
 *
 * 2026-10-27 yslee - 폼 로그인 대신 토큰 로그인(POST /api/v1/auth/login)으로 검증한다(#419 웨이브 C)
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAuditFailureIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearRefreshTokens() {
        jdbc.update("DELETE FROM fgc.auth_refresh_token");
    }

    /**
     * 설명 : 실제 감사 DB 저장이 실패해도 정상 로그인(토큰 발급)이 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.1
     * @since 2026-09-26
     */
    @Test
    void auditStorageFailureDoesNotBreakSuccessfulAuthentication() throws Exception {
        long before = auditCount();

        // inet 값 저장 실패를 실제 PostgreSQL에서 유발한다. 인증 조회는 이미 성공한 상태다.
        mvc.perform(post("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost").header("X-FGC-Client", "web")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"settle01\",\"password\":\"fgc1234!\"}")
                        .with(request -> {
                            request.setRemoteAddr("invalid-ip");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        assertThat(auditCount()).isEqualTo(before);
    }

    /**
     * 설명 : 로그인 성공 감사로그의 전체 건수를 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private long auditCount() {
        return jdbc.queryForObject("select count(*) from fgc.audit_log where action_code = 'LOGIN_SUCCESS'",
                Long.class);
    }
}
