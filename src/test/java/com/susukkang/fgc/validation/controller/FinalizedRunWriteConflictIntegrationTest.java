package com.susukkang.fgc.validation.controller;

import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.AuthTokenService;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : SAR-003 #677 시나리오 3 "FINALIZED 수정 시도 → 409". 실제 토큰과 DB 로 HTTP 응답까지 본다.
 * DB 트리거 자체(V7·V30·V31)와 정정 경로는 FinalizedValidationRunImmutabilityIntegrationTest·JournalCorrectionServiceIntegrationTest 가 지킨다.
 *
 * @author yslee
 * @since 2026-11-04
 * @version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalizedRunWriteConflictIntegrationTest {

    private static final LocalDate MONTH = LocalDate.of(2097, 3, 1);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthTokenService tokens;
    @Autowired
    private FgcUserDetailsService users;

    private String settlement;
    private String gaAdmin;
    private Long finalizedRunId;

    /** 시험 트랜잭션 밖에서 커밋한다. login() 의 app_user 잠금을 쥔 채 요청하면 요청 안 REQUIRES_NEW 쓰기가 그 잠금을 기다린다. */
    @BeforeAll
    void login() {
        settlement = bearer("settle01");
        gaAdmin = bearer("gaadmin");
    }

    @AfterAll
    void clearRefreshTokens() {
        jdbc.update("""
                DELETE FROM fgc.auth_refresh_token WHERE user_id IN
                    (SELECT user_id FROM fgc.app_user WHERE login_id IN ('settle01', 'gaadmin'))
                """);
    }

    @BeforeEach
    void finalizeARun() {
        finalizedRunId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, 1, 'PRE_CONFIRM', 'CREATED') RETURNING validation_run_id
                """, Long.class, MONTH);
        // guard_run_lifecycle 이 허용하는 전이만 밟는다.
        jdbc.update("UPDATE fgc.validation_run SET status = 'RUNNING' WHERE validation_run_id = ?", finalizedRunId);
        jdbc.update("UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = now() WHERE validation_run_id = ?", finalizedRunId);
        jdbc.update("UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = now() WHERE validation_run_id = ?", finalizedRunId);
    }

    @Test
    void executingAFinalizedRunIsConflict() throws Exception {
        mvc.perform(post("/api/v1/validation-runs/{id}/execute", finalizedRunId)
                        .header(HttpHeaders.AUTHORIZATION, settlement))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-VRUN-003"));
        assertThat(runStatus()).isEqualTo("FINALIZED");
    }

    @Test
    void finalizingAgainWithAnotherKeyIsConflict() throws Exception {
        mvc.perform(post("/api/v1/validation-runs/{id}/finalize", finalizedRunId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, gaAdmin))
                .andExpect(status().isConflict());
        assertThat(runStatus()).isEqualTo("FINALIZED");
    }

    @Test
    void newReconciliationUnderAFinalizedRunIsConflict() throws Exception {
        Long insurerId = jdbc.queryForObject("SELECT min(insurer_id) FROM fgc.insurer", Long.class);
        int runsBefore = reconciliationRuns();

        mvc.perform(post("/api/v1/reconciliations")
                        .header(HttpHeaders.AUTHORIZATION, settlement)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"settlementMonth":"2097-03","paymentStage":"GA_TO_FC","insurerId":%d,"validationRunId":%d}"""
                                .formatted(insurerId, finalizedRunId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-VRUN-003"));
        assertThat(reconciliationRuns()).isEqualTo(runsBefore);
    }

    private String bearer(String loginId) {
        return "Bearer " + tokens.login((FgcUserDetails) users.loadUserByUsername(loginId)).accessToken();
    }

    private String runStatus() {
        return jdbc.queryForObject("SELECT status FROM fgc.validation_run WHERE validation_run_id = ?", String.class, finalizedRunId);
    }

    private int reconciliationRuns() {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.reconciliation_run WHERE validation_run_id = ?", Integer.class, finalizedRunId);
    }
}
