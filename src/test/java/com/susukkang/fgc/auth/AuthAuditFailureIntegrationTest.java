package com.susukkang.fgc.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * 설명 : 감사로그 저장 실패 시에도 기존 로그인 흐름이 유지되는지 검증하는 통합 테스트.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAuditFailureIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 설명 : 실제 감사 DB 저장이 실패해도 정상 로그인과 홈 화면 이동이 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Test
    void auditStorageFailureDoesNotBreakSuccessfulAuthentication() throws Exception {
        long before = auditCount();

        // inet 값 저장 실패를 실제 PostgreSQL에서 유발한다. 인증 조회는 이미 성공한 상태다.
        mvc.perform(post("/login").param("username", "settle01").param("password", "fgc1234!")
                        .with(csrf()).with(request -> {
                            request.setRemoteAddr("invalid-ip");
                            return request;
                        }))
                .andExpect(authenticated().withUsername("settle01").withRoles("SETTLEMENT"))
                .andExpect(redirectedUrl("/"));

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
