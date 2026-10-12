package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IF-API-51을 실제 트랜잭션·DB 트리거·감사로그와 함께 검증한다. */
@SpringBootTest
@Transactional
class ValidationRunFinalizationServiceIntegrationTest {

    @Autowired
    private ValidationRunFinalizationService validationRunFinalizationService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @WithMockUser(username = "admin", roles = "SYSTEM_ADMIN")
    void finalizesAtomicallyAndReplaysTheOriginalResultForTheSameKey() {
        Long userId = systemAdminUserId();
        Long runId = createCompletedRun(LocalDate.of(2087, 1, 1));
        String key = "FUN044-" + UUID.randomUUID();

        FinalizeValidationRunResponse first =
                validationRunFinalizationService.finalizeRun(runId, userId, key);
        FinalizeValidationRunResponse replay =
                validationRunFinalizationService.finalizeRun(runId, userId, key);

        assertThat(first.status()).isEqualTo("FINALIZED");
        assertThat(first.finalizedAt()).isNotNull();
        assertThat(first.finalizedBy()).isNotBlank();
        assertThat(replay).isEqualTo(first);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, current_step, finalized_by, finalized_at, finalize_idempotency_key
                  FROM fgc.validation_run
                 WHERE validation_run_id = ?
                """, runId))
                .containsEntry("status", "FINALIZED")
                .containsEntry("current_step", 10)
                .containsEntry("finalized_by", userId)
                .containsEntry("finalize_idempotency_key", key);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.audit_log
                 WHERE entity_type = 'VALIDATION_RUN'
                   AND entity_id = ?
                   AND action_code = 'VALIDATION_RUN_FINALIZED'
                """, Long.class, String.valueOf(runId))).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "admin", roles = "SYSTEM_ADMIN")
    void leavesRunCompletedWhenChecklistHasAnUnresolvedCriticalException() {
        LocalDate month = LocalDate.of(2087, 2, 1);
        Long runId = createCompletedRun(month);
        // #330: 배치 검출 경로(record_exception_detection)는 항상 validation_month 를 남기고,
        // 확정 게이트 조건 3도 그 월 기준으로 센다 — 픽스처도 실제 검출 행과 같게 월을 채운다.
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, severity, status, validation_run_id,
                    validation_month, source_entity_type, source_entity_id, title
                ) VALUES (?, 'OTHER', 'CRITICAL', 'NEW', ?, ?, 'VALIDATION_RUN', ?, '확정 차단 예외')
                """, "FUN044-BLOCK-" + UUID.randomUUID(), runId, month, String.valueOf(runId));

        assertThatThrownBy(() -> validationRunFinalizationService.finalizeRun(
                runId, systemAdminUserId(), "FUN044-BLOCK-KEY-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_002);
                    assertThat(exception.getParams()).containsEntry("n", 1L);
                });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.validation_run WHERE validation_run_id = ?",
                String.class, runId)).isEqualTo("COMPLETED");
    }

    // 2026-10-11 yslee - #491 같은 달을 다시 검증했으면 이전 실행은 확정할 수 없고 최신 실행만 확정된다
    // (확정에 실제로 쓰이는 ValidationRunRepository 네이티브 쿼리 경로)
    @Test
    @WithMockUser(username = "admin", roles = "SYSTEM_ADMIN")
    void rejectsSupersededRunAndFinalizesOnlyTheLatestRunOfTheMonth() {
        LocalDate month = LocalDate.of(2087, 4, 1);
        Long olderRunId = createCompletedRun(month);
        Long latestRunId = createCompletedRun(month);
        Long userId = systemAdminUserId();

        assertThat(validationRunFinalizationService.getChecklist(olderRunId).conditions().get(6))
                .satisfies(condition -> {
                    assertThat(condition.passed()).isFalse();
                    assertThat(condition.linkUrl()).isEqualTo("/validation-runs/" + latestRunId);
                });
        assertThatThrownBy(() -> validationRunFinalizationService.finalizeRun(
                olderRunId, userId, "FUN044-SUPERSEDED-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_002);
                    assertThat(exception.getParams()).containsEntry("n", 1L);
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.validation_run WHERE validation_run_id = ?",
                String.class, olderRunId)).isEqualTo("COMPLETED");

        assertThat(validationRunFinalizationService.finalizeRun(
                latestRunId, userId, "FUN044-LATEST-" + UUID.randomUUID()).status()).isEqualTo("FINALIZED");
    }

    @Test
    @WithMockUser(username = "settle01", roles = "SETTLEMENT")
    void serviceLayerAlsoRejectsSettlementRole() {
        Long runId = createCompletedRun(LocalDate.of(2087, 3, 1));

        assertThatThrownBy(() -> validationRunFinalizationService.finalizeRun(
                runId, systemAdminUserId(), "FUN044-DENIED-" + UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Long createCompletedRun(LocalDate month) {
        int runNo = ThreadLocalRandom.current().nextInt(1, 1_000_000);
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        return runId;
    }

    private Long systemAdminUserId() {
        return jdbcTemplate.queryForObject("""
                SELECT u.user_id
                  FROM fgc.app_user u
                  JOIN fgc.app_role r ON r.role_id = u.role_id
                 WHERE r.role_code = 'SYSTEM_ADMIN'
                 ORDER BY u.user_id LIMIT 1
                """, Long.class);
    }
}
