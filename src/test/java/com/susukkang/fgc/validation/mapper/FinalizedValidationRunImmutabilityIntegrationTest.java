package com.susukkang.fgc.validation.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "FINALIZED 상태의 검증 실행과 그 하위 결과가 재실행으로 변경되지 않는지 검증".
 *
 * 이 불변성은 애플리케이션 코드가 아니라 DB 트리거(trg_validation_run_finalized,
 * guard_finalized_validation_result — V1__baseline_v2_1_2.sql:1570-1648)가 보장한다.
 * CreateDailyRunTasklet의 FINALIZED 분기(existingFinalizedRunCausesFreshRunToBeCreated...
 * WithoutTouchingTheFinalizedRow)는 "우리 애플리케이션이 FINALIZED 행을 건드리지 않는다"만
 * 증명하고, "DB가 애초에 그걸 허용 안 한다"는 이 트리거 자체는 아직 테스트가 없었다 —
 * 여기서 직접 raw SQL로 우회 시도를 해서 확인한다.
 *
 * @Transactional로 테스트 종료 시 자동 롤백시킨다 — FINALIZED 행은 트리거가 DELETE
 * 자체를 막아 수동 정리가 불가능했지만(코드리뷰 반영), 롤백은 트리거를 거치지 않고
 * 트랜잭션 전체를 되돌리므로 FINALIZED 행도 문제없이 정리된다. 각 테스트가 기대하는
 * 예외(assertThatThrownBy)는 항상 메서드의 마지막 statement이므로, 트리거가 트랜잭션을
 * abort 상태로 만들어도 이후 같은 트랜잭션 내 추가 DB 접근은 없다.
 */
@SpringBootTest
@Transactional
class FinalizedValidationRunImmutabilityIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long validationRunId;
    private Long capCheckId;

    private Long contractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202607-0001");
    }

    private Long capRuleSetId() {
        return jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set LIMIT 1", Long.class);
    }

    /**
     * guard_run_lifecycle이 허용하는 전이만 한 단계씩 밟아 RUNNING까지만 이동한다(결과행을
     * 이 시점에 끼워 넣을 수 있게) — FINALIZED로의 마지막 전이는 호출자가 별도로 한다.
     *
     * run_type을 PRE_CONFIRM으로 쓰는 이유: MONTHLY는 uq_validation_run_active_month가
     * "월당 활성 실행 1건"을 강제해서, 같은 달로 여러 테스트가 각자 CREATED/RUNNING 행을
     * 만들면(특히 이전 실행에서 실패해 정리가 안 된 행이 남아있으면) 서로 충돌한다 — 이
     * 테스트의 관심사(FINALIZED 불변성)와 무관한 제약이라 PRE_CONFIRM으로 피해간다.
     *
     * current_step을 각 전이마다 명시적으로 맞추는 이유: ck_validation_run_step CHECK
     * 제약이 status와 current_step 조합을 강제한다(CREATED=0, RUNNING/FAILED=0~8,
     * COMPLETED=8, FINALIZED=10) — 실제 서비스는 각 Step 완료마다 이걸 채우지만, 여기선
     * raw SQL로 상태만 빨리 넘기므로 값을 직접 맞춰줘야 한다.
     */
    private Long createRunningValidationRun() {
        int runNo = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 1_000_000);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, 'PRE_CONFIRM', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, LocalDate.of(2031, 5, 1), runNo);
        jdbcTemplate.update(
                "UPDATE fgc.validation_run SET status='RUNNING', current_step=1, started_at=now() WHERE validation_run_id=?", id);
        return id;
    }

    private void finalizeValidationRun(Long id) {
        jdbcTemplate.update(
                "UPDATE fgc.validation_run SET status='COMPLETED', current_step=8, completed_at=now() WHERE validation_run_id=?", id);
        jdbcTemplate.update(
                "UPDATE fgc.validation_run SET status='FINALIZED', current_step=10, finalized_at=now() WHERE validation_run_id=?", id);
    }

    private Long createFinalizedValidationRun() {
        Long id = createRunningValidationRun();
        finalizeValidationRun(id);
        return id;
    }

    @Test
    void updatingAFinalizedValidationRunIsRejectedByDbTrigger() {
        validationRunId = createFinalizedValidationRun();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", validationRunId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void deletingAFinalizedValidationRunIsRejectedByDbTrigger() {
        validationRunId = createFinalizedValidationRun();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM fgc.validation_run WHERE validation_run_id=?", validationRunId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void updatingCapCheckUnderAFinalizedRunIsRejectedByDbTrigger() {
        // cap_check는 run이 아직 RUNNING일 때 끼워 넣는다 — guard_finalized_validation_result는
        // INSERT 시점에도 대상 run이 FINALIZED면 막으므로, FINALIZED로 전이하기 전에 넣어야 한다.
        validationRunId = createRunningValidationRun();
        capCheckId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_check
                    (validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind,
                     as_of_date, base_premium_amount, limit_amount, included_amount, remaining_amount,
                     result_status)
                VALUES (?, ?, 'GA_TO_FC', ?, 'MONTHLY', ?, 100000, 1200000, 0, 1200000, 'NORMAL')
                RETURNING cap_check_id
                """, Long.class, validationRunId, contractId(), capRuleSetId(), LocalDate.of(2031, 5, 10));
        assertThat(capCheckId).isNotNull();

        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fgc.cap_check SET result_status='VIOLATION' WHERE cap_check_id=?", capCheckId))
                .isInstanceOf(DataAccessException.class);
    }

    // trg_cap_check_immutable(V1__baseline_v2_1_2.sql:1559-1649)은 UPDATE와 DELETE를 함께
    // 막는다 — 위 UPDATE 테스트만으로는 DELETE 차단까지 검증되지 않아 추가한다(코드리뷰 반영).
    @Test
    void deletingCapCheckUnderAFinalizedRunIsRejectedByDbTrigger() {
        validationRunId = createRunningValidationRun();
        capCheckId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_check
                    (validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind,
                     as_of_date, base_premium_amount, limit_amount, included_amount, remaining_amount,
                     result_status)
                VALUES (?, ?, 'GA_TO_FC', ?, 'MONTHLY', ?, 100000, 1200000, 0, 1200000, 'NORMAL')
                RETURNING cap_check_id
                """, Long.class, validationRunId, contractId(), capRuleSetId(), LocalDate.of(2031, 5, 10));
        assertThat(capCheckId).isNotNull();

        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM fgc.cap_check WHERE cap_check_id=?", capCheckId))
                .isInstanceOf(DataAccessException.class);
    }
}
