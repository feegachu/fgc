package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.validation.batch.contract.ExceptionGenerationPort;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.ValidationRunResultSummaryRow;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요구사항 : FGC-FUN-044 "동일 월 재검증은 새 실행번호로 생성되어 이전 결과와 비교할 수 있다" (#498)
 *
 * 설명 : 확정된 달을 다시 검증한 실행의 8단계(예외 생성)가 확정 실행과 1,200% 판정을 비교해, 판정·한도액이
 * 다른 계약·지급단계만 CAP_REVIEW_REQUIRED / FINALIZED_RESULT_DIFF 예외로 남기는지 실제 PostgreSQL 함수·제약으로
 * 확인한다. 테스트 트랜잭션은 롤백되므로 확정 행도 남지 않는다.
 *
 * @author yslee
 * @since 2026-10-12
 */
@SpringBootTest
@Transactional
class FinalizedCapResultDiffIntegrationTest {

    private static final LocalDate MONTH = LocalDate.of(2083, 3, 1);
    private static final BigDecimal LIMIT = new BigDecimal("1200000.00");

    @Autowired
    private ExceptionGenerationPort exceptionGenerationPort;
    @Autowired
    private ExceptionCaseRepository exceptionCaseRepository;
    @Autowired
    private ValidationRunRepository validationRunRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<Long> contractIds;
    private Long capRuleSetId;

    @BeforeEach
    void setUp() {
        contractIds = jdbcTemplate.queryForList(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 3", Long.class);
        assertThat(contractIds).hasSize(3);
        capRuleSetId = jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set ORDER BY cap_rule_set_id LIMIT 1", Long.class);
    }

    @Test
    void sameResultAsFinalizedRunRecordsNothing() {
        Long finalizedRunId = runningRun(1);
        insertCapCheck(finalizedRunId, contractIds.get(0), "INSURER_TO_GA", "NORMAL", LIMIT);
        finalize(finalizedRunId);

        Long revalidationRunId = runningRun(2);
        insertCapCheck(revalidationRunId, contractIds.get(0), "INSURER_TO_GA", "NORMAL", LIMIT);
        generate(revalidationRunId);

        assertThat(diffCaseCount()).isZero();
        ValidationRunResultSummaryRow summary = validationRunRepository.summarize(revalidationRunId).orElseThrow();
        assertThat(summary.getComparedFinalizedRunId()).isEqualTo(finalizedRunId);
        assertThat(summary.getFinalizedResultDiffCount()).isZero();
    }

    // #468 사례: 교정 전 식으로 정상이던 계약이 재검증에서 위반으로 바뀜
    @Test
    void recordsStatusAndLimitDifferencesAgainstFinalizedRunOnly() {
        Long finalizedRunId = runningRun(1);
        insertCapCheck(finalizedRunId, contractIds.get(0), "INSURER_TO_GA", "NORMAL", LIMIT);
        insertCapCheck(finalizedRunId, contractIds.get(1), "GA_TO_FC", "NORMAL", LIMIT);
        insertCapCheck(finalizedRunId, contractIds.get(2), "GA_TO_FC", "NORMAL", LIMIT);
        finalize(finalizedRunId);

        Long revalidationRunId = runningRun(2);
        Long violationCapCheckId =
                insertCapCheck(revalidationRunId, contractIds.get(0), "INSURER_TO_GA", "VIOLATION", LIMIT);
        insertCapCheck(revalidationRunId, contractIds.get(1), "GA_TO_FC", "NORMAL", new BigDecimal("1000000.00"));
        insertCapCheck(revalidationRunId, contractIds.get(2), "GA_TO_FC", "NORMAL", LIMIT);
        // 확정 실행에 없던 지급단계는 비교하지 않는다(#494 이전 계약수동 확정 실행의 부분 계산 오탐 방지)
        insertCapCheck(revalidationRunId, contractIds.get(2), "INSURER_TO_GA", "VIOLATION", LIMIT);
        generate(revalidationRunId);

        List<Map<String, Object>> cases = jdbcTemplate.queryForList("""
                SELECT contract_id, payment_stage_key, exception_type, severity, status, validation_run_id,
                       source_entity_type, source_entity_id, title
                  FROM (SELECT ec.*, SPLIT_PART(ec.exception_key, ':', 5) AS payment_stage_key
                          FROM fgc.exception_case ec
                         WHERE ec.validation_month = ? AND ec.reason_code = 'FINALIZED_RESULT_DIFF') diff
                 ORDER BY contract_id
                """, MONTH);
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0))
                .containsEntry("contract_id", contractIds.get(0))
                .containsEntry("payment_stage_key", "INSURER_TO_GA")
                .containsEntry("exception_type", "CAP_REVIEW_REQUIRED")
                .containsEntry("severity", "HIGH")
                .containsEntry("status", "NEW")
                .containsEntry("validation_run_id", revalidationRunId)
                .containsEntry("source_entity_type", "CAP_CHECK")
                .containsEntry("source_entity_id", String.valueOf(violationCapCheckId))
                .containsEntry("title", "확정 결과와 1,200% 판정 다름 — 정상 → 위반");
        assertThat(cases.get(1))
                .containsEntry("contract_id", contractIds.get(1))
                .containsEntry("title", "확정 결과와 1,200% 한도액 다름 — 1,200,000원 → 1,000,000원");

        Map<String, Object> evidence = jdbcTemplate.queryForMap("""
                SELECT (o.evidence_snapshot ->> 'finalizedRunId')::bigint AS finalized_run_id,
                       o.evidence_snapshot ->> 'finalizedResultStatus' AS before_status,
                       o.evidence_snapshot ->> 'resultStatus' AS after_status
                  FROM fgc.exception_occurrence o
                  JOIN fgc.exception_case ec ON ec.exception_case_id = o.exception_case_id
                 WHERE ec.reason_code = 'FINALIZED_RESULT_DIFF' AND ec.contract_id = ?
                   AND o.validation_run_id = ?
                """, contractIds.get(0), revalidationRunId);
        assertThat(evidence)
                .containsEntry("finalized_run_id", finalizedRunId)
                .containsEntry("before_status", "NORMAL")
                .containsEntry("after_status", "VIOLATION");

        // 같은 실행을 다시 처리해도(Step 재시작) 업무건·실행별 이력이 늘지 않는다
        assertThat(exceptionCaseRepository.insertFromFinalizedCapResultDiffs(revalidationRunId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_occurrence o
                  JOIN fgc.exception_case ec ON ec.exception_case_id = o.exception_case_id
                 WHERE ec.reason_code = 'FINALIZED_RESULT_DIFF' AND ec.validation_month = ?
                """, Long.class, MONTH)).isEqualTo(2L);

        ValidationRunResultSummaryRow summary = validationRunRepository.summarize(revalidationRunId).orElseThrow();
        assertThat(summary.getComparedFinalizedRunId()).isEqualTo(finalizedRunId);
        assertThat(summary.getFinalizedResultDiffCount()).isEqualTo(2L);
        // 확정 결과 자체는 바뀌지 않는다(SRC-027 D-02)
        assertThat(jdbcTemplate.queryForObject("""
                SELECT result_status FROM fgc.cap_check
                 WHERE validation_run_id = ? AND contract_id = ? AND payment_stage = 'INSURER_TO_GA'
                """, String.class, finalizedRunId, contractIds.get(0))).isEqualTo("NORMAL");
    }

    // 다음 재검증에서 같은 차이가 다시 나오면 새 업무건이 아니라 같은 건의 검출 이력이 는다
    @Test
    void laterRevalidationWithSameDifferenceIsRecurrenceOfSameCase() {
        Long finalizedRunId = runningRun(1);
        insertCapCheck(finalizedRunId, contractIds.get(0), "INSURER_TO_GA", "NORMAL", LIMIT);
        finalize(finalizedRunId);

        Long secondRunId = runningRun(2);
        insertCapCheck(secondRunId, contractIds.get(0), "INSURER_TO_GA", "VIOLATION", LIMIT);
        assertThat(exceptionCaseRepository.insertFromFinalizedCapResultDiffs(secondRunId)).isEqualTo(1L);
        Long thirdRunId = runningRun(3);
        insertCapCheck(thirdRunId, contractIds.get(0), "INSURER_TO_GA", "VIOLATION", LIMIT);
        assertThat(exceptionCaseRepository.insertFromFinalizedCapResultDiffs(thirdRunId)).isZero();

        assertThat(jdbcTemplate.queryForMap("""
                SELECT detection_count, first_detected_run_id, last_detected_run_id
                  FROM fgc.exception_case WHERE validation_month = ? AND reason_code = 'FINALIZED_RESULT_DIFF'
                """, MONTH))
                .containsEntry("detection_count", 2)
                .containsEntry("first_detected_run_id", secondRunId)
                .containsEntry("last_detected_run_id", thirdRunId);
    }

    // 운영정책서 제44조 종결 방식: 확정 결과는 바뀌지 않아 재검증마다 같은 차이가 다시 검출된다.
    // 확정 유지로 반려(REJECTED)한 건은 증거가 같으면 닫힌 채 남고, 해결(RESOLVED)로 닫은 건은 NEW로 다시 열린다(V24).
    @Test
    void rejectedCaseStaysClosedButResolvedCaseReopensOnSameDifference() {
        Long finalizedRunId = runningRun(1);
        insertCapCheck(finalizedRunId, contractIds.get(0), "INSURER_TO_GA", "NORMAL", LIMIT);
        insertCapCheck(finalizedRunId, contractIds.get(1), "INSURER_TO_GA", "NORMAL", LIMIT);
        finalize(finalizedRunId);

        Long secondRunId = runningRun(2);
        insertCapCheck(secondRunId, contractIds.get(0), "INSURER_TO_GA", "VIOLATION", LIMIT);
        insertCapCheck(secondRunId, contractIds.get(1), "INSURER_TO_GA", "VIOLATION", LIMIT);
        exceptionCaseRepository.insertFromFinalizedCapResultDiffs(secondRunId);
        Long rejectedCaseId = diffCaseId(contractIds.get(0));
        Long resolvedCaseId = diffCaseId(contractIds.get(1));
        exceptionCaseRepository.updateCaseAfterAction(rejectedCaseId, ExceptionStatus.REJECTED, null, OffsetDateTime.now());
        exceptionCaseRepository.updateCaseAfterAction(resolvedCaseId, ExceptionStatus.RESOLVED, null, OffsetDateTime.now());

        Long thirdRunId = runningRun(3);
        insertCapCheck(thirdRunId, contractIds.get(0), "INSURER_TO_GA", "VIOLATION", LIMIT);
        insertCapCheck(thirdRunId, contractIds.get(1), "INSURER_TO_GA", "VIOLATION", LIMIT);
        exceptionCaseRepository.insertFromFinalizedCapResultDiffs(thirdRunId);

        assertThat(caseStatus(rejectedCaseId)).isEqualTo("REJECTED");
        assertThat(caseStatus(resolvedCaseId)).isEqualTo("NEW");
    }

    private Long diffCaseId(Long contractId) {
        return jdbcTemplate.queryForObject("""
                SELECT exception_case_id FROM fgc.exception_case
                 WHERE validation_month = ? AND reason_code = 'FINALIZED_RESULT_DIFF' AND contract_id = ?
                """, Long.class, MONTH, contractId);
    }

    private String caseStatus(Long caseId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_case_id = ?", String.class, caseId);
    }

    @Test
    void monthWithoutFinalizedRunHasNoComparisonBasis() {
        Long earlierRunId = runningRun(1);
        insertCapCheck(earlierRunId, contractIds.get(0), "INSURER_TO_GA", "NORMAL", LIMIT);
        Long laterRunId = runningRun(2);
        insertCapCheck(laterRunId, contractIds.get(0), "INSURER_TO_GA", "VIOLATION", LIMIT);

        assertThat(exceptionCaseRepository.insertFromFinalizedCapResultDiffs(laterRunId)).isZero();
        assertThat(diffCaseCount()).isZero();
        assertThat(validationRunRepository.summarize(laterRunId).orElseThrow().getComparedFinalizedRunId()).isNull();
    }

    private void generate(Long runId) {
        exceptionGenerationPort.generate(new ValidationStepContext(runId,
                new ValidationJobContext(MONTH, 2, ValidationRunType.PRE_CONFIRM, 1L, "req-498")));
    }

    private long diffCaseCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_case
                 WHERE validation_month = ? AND reason_code = 'FINALIZED_RESULT_DIFF'
                """, Long.class, MONTH);
    }

    // 월당 활성 MONTHLY 실행 제약(uq_validation_run_active_month)과 겹치지 않게 PRE_CONFIRM 으로 만든다
    private Long runningRun(int runNo) {
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM') RETURNING validation_run_id
                """, Long.class, MONTH, runNo);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        return runId;
    }

    private void finalize(Long runId) {
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'FINALIZED', current_step = 10, finalized_at = clock_timestamp(),
                       finalized_by = (SELECT MIN(user_id) FROM fgc.app_user)
                 WHERE validation_run_id = ?
                """, runId);
    }

    private Long insertCapCheck(Long runId, Long contractId, String paymentStage, String resultStatus,
                                BigDecimal limitAmount) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_check (
                    validation_run_id, contract_id, payment_stage, cap_rule_set_id,
                    check_kind, as_of_date, base_premium_amount, limit_amount, included_amount,
                    remaining_amount, result_status
                ) VALUES (?, ?, ?, ?, 'MONTHLY', ?, 100000, ?, 0, ?, ?)
                RETURNING cap_check_id
                """, Long.class, runId, contractId, paymentStage, capRuleSetId,
                MONTH.plusMonths(1).minusDays(1), limitAmount, limitAmount, resultStatus);
    }
}
