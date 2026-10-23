package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요구사항 : FGC-FUN-044 검증 결과 확정·잠금 (#494 월 대표 확정 규칙)
 *
 * 설명 : 운영정책서 제44조 — 월 전체를 계산한 실행(MONTHLY·PRE_CONFIRM)만 그 달을 대표해 확정하고,
 * 확정은 한 달에 한 건이다. 확정된 달에도 재검증 실행은 만들 수 있지만(FUN-044 "동일 월 재검증은 새 실행번호로
 * 생성되어 이전 결과와 비교") 그 실행은 확정하지 않는다. 실제 PostgreSQL 트랜잭션·트리거 경로로 검증한다.
 *
 * 실행 생성은 REQUIRES_NEW 새 트랜잭션에서 돌므로 확정이 실제로 커밋돼 있어야 한다. 그래서 @Transactional
 * 롤백을 쓰지 않는다. 확정(FINALIZED) 행은 트리거상 지울 수 없으므로 이 테스트 전용 검증월(2084년)에 남기고,
 * 이 테스트가 생성한 미확정 행만 정리한다. 같은 월의 배치 실행은 예외 이력을 참조할 수 있으므로
 * 월 전체를 삭제해서는 안 된다. Testcontainers 또는 폐기할 격리 DB에서 실행한다.
 *
 * @author yslee
 * @since 2026-10-12
 */
@SpringBootTest
class ValidationRunMonthRepresentativeIntegrationTest {

    private static final LocalDate FINALIZED_MONTH = LocalDate.of(2084, 1, 1);
    private static final LocalDate MANUAL_MONTH = LocalDate.of(2084, 2, 1);

    private final List<Long> createdRunIds = new ArrayList<>();

    @Autowired
    private ValidationRunFinalizationService finalizationService;
    @Autowired
    private ValidationRunCreateService createService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        createdRunIds.forEach(id -> jdbcTemplate.update("""
                DELETE FROM fgc.validation_run
                 WHERE validation_run_id = ? AND status <> 'FINALIZED'
                """, id));
        createdRunIds.clear();
    }

    // ② 확정된 달에도 재검증 실행은 만들어 비교할 수 있지만(FUN-044), 그 실행은 확정하지 않는다(조건 8)
    @Test
    @WithMockUser(username = "admin", roles = "SYSTEM_ADMIN")
    void allowsRevalidationRunAfterFinalizationButRejectsFinalizingIt() {
        Long finalizedRunId = createCompletedRun(FINALIZED_MONTH, "PRE_CONFIRM");
        finalizationService.finalizeRun(finalizedRunId, systemAdminUserId(), "FUN044-REP-" + UUID.randomUUID());

        ValidationRunRow created = createService.create(
                new CreateValidationRunCommand(FINALIZED_MONTH, ValidationRunType.MONTHLY, null));
        createdRunIds.add(created.getValidationRunId());
        assertThat(created.getStatus()).isEqualTo("CREATED");

        Long revalidationRunId = createCompletedRun(FINALIZED_MONTH, "PRE_CONFIRM");
        assertThat(finalizationService.getChecklist(revalidationRunId).conditions().get(7))
                .satisfies(condition -> {
                    assertThat(condition.passed()).isFalse();
                    assertThat(condition.linkUrl()).isEqualTo("/validation-runs/" + finalizedRunId);
                });
        assertThatThrownBy(() -> finalizationService.finalizeRun(
                revalidationRunId, systemAdminUserId(), "FUN044-REP2-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(FgcBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_002);
                    assertThat(e.getParams()).containsEntry("n", 1L);
                });
        assertThat(countFinalized(FINALIZED_MONTH)).isEqualTo(1);
    }

    // ① 변경된 계약만 계산하는 계약수동 실행은 COMPLETED/8 이어도 확정하지 않는다
    @Test
    @WithMockUser(username = "admin", roles = "SYSTEM_ADMIN")
    void rejectsFinalizingManualContractRunAndKeepsItCompleted() {
        Long runId = createCompletedRun(MANUAL_MONTH, "MANUAL_CONTRACT");

        assertThatThrownBy(() -> finalizationService.finalizeRun(
                runId, systemAdminUserId(), "FUN044-MANUAL-" + UUID.randomUUID()))
                .isInstanceOfSatisfying(FgcBusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_004));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.validation_run WHERE validation_run_id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void cleanUpPreservesAnotherRunsExceptionHistoryInTheSameMonth() {
        Long otherRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM') RETURNING validation_run_id
                """, Long.class, MANUAL_MONTH, ThreadLocalRandom.current().nextInt(1, 1_000_000));
        try {
            Long otherCaseId = jdbcTemplate.queryForObject("""
                    INSERT INTO fgc.exception_case
                        (exception_key, exception_type, severity, validation_run_id,
                         source_entity_type, source_entity_id, title)
                    VALUES (?, 'DATA_QUALITY', 'WARNING', ?, 'IT', ?, '월 대표 테스트 정리 격리 검증')
                    RETURNING exception_case_id
                    """, Long.class, "IT-MONTH-CLEANUP:" + UUID.randomUUID(), otherRunId, otherRunId.toString());
            Long ownedRunId = createCompletedRun(MANUAL_MONTH, "MANUAL_CONTRACT");

            cleanUp();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                    Long.class, ownedRunId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                    Long.class, otherRunId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.exception_case WHERE exception_case_id = ?",
                    Long.class, otherCaseId)).isEqualTo(1);
        } finally {
            // 이 메서드가 만든 충돌 재현용 데이터만 정리한다. 실제 배치 이력은 건드리지 않는다.
            jdbcTemplate.update("DELETE FROM fgc.exception_case WHERE validation_run_id = ?", otherRunId);
            jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", otherRunId);
        }
    }

    private long countFinalized(LocalDate month) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_run WHERE validation_month = ? AND status = 'FINALIZED'",
                Long.class, month);
    }

    private Long createCompletedRun(LocalDate month, String runType) {
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, ?)
                RETURNING validation_run_id
                """, Long.class, month, ThreadLocalRandom.current().nextInt(1, 1_000_000), runType);
        createdRunIds.add(runId);
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
