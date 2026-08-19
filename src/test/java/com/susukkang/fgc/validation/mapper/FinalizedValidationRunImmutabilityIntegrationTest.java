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
 * "FINALIZED 상태의 검증 실행과 그 하위 결과가 재실행으로 변경되지 않는지 검증"
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

    @Test
    void updatingReconciliationResultUnderFinalizedValidationRunIsRejected() {
        validationRunId = createRunningValidationRun();
        Long reconciliationRunId = createCompletedReconciliationRun(validationRunId);
        Long reconciliationResultId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, result_type,
                    expected_total_amount, actual_total_amount, difference_amount
                ) VALUES (?, ?, 'MATCHED', 100, 100, 0)
                RETURNING reconciliation_result_id
                """, Long.class, reconciliationRunId, "FUN044-RECO-" + validationRunId);
        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fgc.reconciliation_result
                   SET detail_snapshot = '{"tampered":true}'::jsonb
                 WHERE reconciliation_result_id = ?
                """, reconciliationResultId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finalized validation run");
    }

    @Test
    void updatingReconciliationMatchUnderFinalizedValidationRunIsRejected() {
        validationRunId = createRunningValidationRun();
        Long reconciliationRunId = createCompletedReconciliationRun(validationRunId);
        Long reconciliationResultId = createMatchedReconciliationResult(reconciliationRunId, "MATCH");
        Long scheduleLineId = createScheduleLine();
        Long reconciliationMatchId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_match (
                    reconciliation_result_id, match_seq, schedule_line_id,
                    matched_amount, match_role
                ) VALUES (?, 1, ?, 100, 'EXPECTED')
                RETURNING reconciliation_match_id
                """, Long.class, reconciliationResultId, scheduleLineId);
        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fgc.reconciliation_match
                   SET matched_amount = 90
                 WHERE reconciliation_match_id = ?
                """, reconciliationMatchId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finalized validation run");
    }

    @Test
    void movingReconciliationResultAwayFromFinalizedValidationRunIsRejected() {
        validationRunId = createRunningValidationRun();
        Long finalizedReconciliationRunId = createCompletedReconciliationRun(validationRunId);
        Long reconciliationResultId = createMatchedReconciliationResult(
                finalizedReconciliationRunId, "REPARENT");
        Long openValidationRunId = createRunningValidationRun();
        Long openReconciliationRunId = createCompletedReconciliationRun(openValidationRunId);
        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fgc.reconciliation_result
                   SET reconciliation_run_id = ?
                 WHERE reconciliation_result_id = ?
                """, openReconciliationRunId, reconciliationResultId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finalized validation run");
    }

    @Test
    void updatingReconciliationRunUnderFinalizedValidationRunIsRejected() {
        validationRunId = createRunningValidationRun();
        Long reconciliationRunId = createCompletedReconciliationRun(validationRunId);
        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET completed_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, reconciliationRunId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("finalized validation run");
    }

    @Test
    void updatingCapDetailUnderFinalizedRunIsRejectedByDbTrigger() {
        validationRunId = createRunningValidationRun();
        capCheckId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_check (
                    validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind,
                    as_of_date, base_premium_amount, limit_amount, included_amount,
                    remaining_amount, result_status
                ) VALUES (?, ?, 'GA_TO_FC', ?, 'MONTHLY', ?, 100000, 1200000, 100, 1199900, 'NORMAL')
                RETURNING cap_check_id
                """, Long.class, validationRunId, contractId(), capRuleSetId(), LocalDate.of(2031, 5, 10));
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        Long detailId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_check_detail (
                    cap_check_id, detail_seq, commission_item_id, classification_snapshot,
                    amount, decision_reason, item_code, item_name
                ) SELECT ?, 1, commission_item_id, 'INCLUDED', 100, 'test', item_code, item_name
                    FROM fgc.commission_item WHERE commission_item_id = ?
                RETURNING cap_check_detail_id
                """, Long.class, capCheckId, commissionItemId);
        finalizeValidationRun(validationRunId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE fgc.cap_check_detail SET amount = 200 WHERE cap_check_detail_id = ?", detailId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    private Long createCompletedReconciliationRun(Long runId) {
        Long reconciliationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (
                    validation_run_id, settlement_month, payment_stage, status
                ) VALUES (?, DATE '2031-05-01', 'GA_TO_FC', 'CREATED')
                RETURNING reconciliation_run_id
                """, Long.class, runId);
        jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET status = 'RUNNING', started_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, reconciliationRunId);
        jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET status = 'COMPLETED', completed_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, reconciliationRunId);
        return reconciliationRunId;
    }

    private Long createMatchedReconciliationResult(Long reconciliationRunId, String suffix) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, result_type,
                    expected_total_amount, actual_total_amount, difference_amount
                ) VALUES (?, ?, 'MATCHED', 100, 100, 0)
                RETURNING reconciliation_result_id
                """, Long.class, reconciliationRunId,
                "FUN044-RECO-" + suffix + "-" + reconciliationRunId);
    }

    private Long createScheduleLine() {
        Long policyVersionId = jdbcTemplate.queryForObject(
                "SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1",
                Long.class);
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        Integer scheduleVersionNo = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(schedule_version_no), 0) + 1
                  FROM fgc.schedule_header
                 WHERE contract_id = ?
                   AND payment_stage = 'GA_TO_FC'
                   AND schedule_purpose = 'OPERATIONAL'
                """, Integer.class, contractId());
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                ) VALUES (?, 'GA_TO_FC', ?, ?, 'CURRENT', 'OPERATIONAL', false)
                RETURNING schedule_header_id
                """, Long.class, contractId(), policyVersionId, scheduleVersionNo);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type,
                    rate_pct, expected_amount
                ) VALUES (?, 1, 1, 1, DATE '2031-05-01', ?, 'TEST_AMOUNT', 100,
                          'RATE', 100.000000, 100)
                RETURNING schedule_line_id
                """, Long.class, scheduleHeaderId, commissionItemId);
    }
}
