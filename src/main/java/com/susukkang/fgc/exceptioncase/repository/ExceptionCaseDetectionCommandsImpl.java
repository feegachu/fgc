package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateRow;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * 공용 ExceptionCaseRepository의 차익거래 단건·대사 일괄 탐지 fragment.
 * 후보·검토 필요 등록을 기존 DB 함수로 처리하며,
 * 업무키·재검출·재개방·실행별 검출 이력 계약은 fgc.record_exception_detection이 관리한다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ExceptionCaseDetectionCommandsImpl implements ExceptionCaseDetectionCommands {
    private final EntityManager entityManager;

    @Override
    public Long insertArbitrageCandidate(Long validationRunId, Long contractId, Long arbitrageCheckId, String paymentStage, String description) {
        entityManager.flush();
        NativeQuery<?> q = entityManager.createNativeQuery("""
                SELECT recorded.recorded_case_id
                FROM fgc.validation_run vr
                CROSS JOIN LATERAL fgc.record_exception_detection(
                CONCAT('ARBITRAGE_CANDIDATE:', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                ':CONTRACT:', CAST(:contractId AS bigint), ':', CAST(:paymentStage AS varchar)),
                'ARBITRAGE_CANDIDATE', 'ARBITRAGE_LIMIT_EXCEEDED', 'HIGH',
                CAST(:validationRunId AS bigint), CAST(:contractId AS bigint), NULL, NULL,
                'ARBITRAGE_CHECK', CAST(CAST(:arbitrageCheckId AS bigint) AS varchar),
                '차익거래 검토대상', CAST(:description AS varchar),
                jsonb_build_object(
                'arbitrageCheckId', CAST(:arbitrageCheckId AS bigint),
                'paymentStage', CAST(:paymentStage AS varchar),
                'description', CAST(:description AS varchar)
                )
                ) recorded
                WHERE vr.validation_run_id = CAST(:validationRunId AS bigint)
                """).unwrap(NativeQuery.class);
        q.setParameter("validationRunId", validationRunId, Long.class);
        q.setParameter("contractId", contractId, Long.class);
        q.setParameter("arbitrageCheckId", arbitrageCheckId, Long.class);
        q.setParameter("paymentStage", paymentStage, String.class);
        q.setParameter("description", description, String.class);
        List<?> ids = q.getResultList();
        entityManager.clear();
        return ids.isEmpty() ? null : ((Number) ids.getFirst()).longValue();
    }

    @Override
    public Long insertArbitrageReviewCase(String exceptionType, Long validationRunId, Long contractId, Long arbitrageCheckId, String paymentStage, String title, String description) {
        entityManager.flush();
        NativeQuery<?> q = entityManager.createNativeQuery("""
                SELECT recorded.recorded_case_id
                FROM fgc.validation_run vr
                CROSS JOIN LATERAL fgc.record_exception_detection(
                CONCAT(CAST(:exceptionType AS varchar), ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                ':CONTRACT:', CAST(:contractId AS bigint), ':', CAST(:paymentStage AS varchar), ':',
                CASE
                WHEN CAST(:exceptionType AS varchar) = 'REFUND_TABLE_MISSING' THEN 'REFUND_TABLE_MISSING'
                WHEN CAST(:exceptionType AS varchar) = 'PRODUCT_CODE_MISMATCH' THEN 'PRODUCT_CODE_MISMATCH'
                WHEN CAST(:exceptionType AS varchar) = 'POLICY_DUPLICATE' THEN 'POLICY_DUPLICATE'
                WHEN CAST(:description AS varchar) LIKE '%금융 스냅샷이 없습니다%' THEN 'FINANCIAL_SNAPSHOT_MISSING'
                ELSE 'ARBITRAGE_DATA_REVIEW_REQUIRED'
                END),
                CAST(:exceptionType AS varchar),
                CASE
                WHEN CAST(:exceptionType AS varchar) = 'REFUND_TABLE_MISSING' THEN 'REFUND_TABLE_MISSING'
                WHEN CAST(:exceptionType AS varchar) = 'PRODUCT_CODE_MISMATCH' THEN 'PRODUCT_CODE_MISMATCH'
                WHEN CAST(:exceptionType AS varchar) = 'POLICY_DUPLICATE' THEN 'POLICY_DUPLICATE'
                WHEN CAST(:description AS varchar) LIKE '%금융 스냅샷이 없습니다%' THEN 'FINANCIAL_SNAPSHOT_MISSING'
                ELSE 'ARBITRAGE_DATA_REVIEW_REQUIRED'
                END,
                'WARNING', CAST(:validationRunId AS bigint), CAST(:contractId AS bigint), NULL, NULL,
                'ARBITRAGE_CHECK', CAST(CAST(:arbitrageCheckId AS bigint) AS varchar),
                CAST(:title AS varchar), CAST(:description AS varchar),
                jsonb_build_object(
                'arbitrageCheckId', CAST(:arbitrageCheckId AS bigint),
                'paymentStage', CAST(:paymentStage AS varchar),
                'description', CAST(:description AS varchar)
                )
                ) recorded
                WHERE vr.validation_run_id = CAST(:validationRunId AS bigint)
                """).unwrap(NativeQuery.class);
        q.setParameter("exceptionType", exceptionType, String.class);
        q.setParameter("validationRunId", validationRunId, Long.class);
        q.setParameter("contractId", contractId, Long.class);
        q.setParameter("arbitrageCheckId", arbitrageCheckId, Long.class);
        q.setParameter("paymentStage", paymentStage, String.class);
        q.setParameter("title", title, String.class);
        q.setParameter("description", description, String.class);
        List<?> ids = q.getResultList();
        entityManager.clear();
        return ids.isEmpty() ? null : ((Number) ids.getFirst()).longValue();
    }

    @Override
    public ReconciliationExceptionBulkCreateRow bulkCreateFromReconciliationResultsByRun(Long reconciliationRunId) {
        entityManager.flush();
        Object[] counts = (Object[]) entityManager.createNativeQuery("""
                WITH candidates AS (
                            SELECT rr.reconciliation_result_id, rr.contract_id, rr.result_type,
                                   rr.expected_total_amount, rr.actual_total_amount, rr.difference_amount,
                                   rr.primary_reason_code, rr.secondary_reason_codes,
                                   rr.actual_agent_id, rr.expected_agent_id, rr.match_group_key,
                                   rrn.validation_run_id, rrn.payment_stage, rrn.tolerance_policy_version_id,
                                   vr.validation_month
                              FROM fgc.reconciliation_result rr
                              INNER JOIN fgc.reconciliation_run rrn
                                  ON rrn.reconciliation_run_id = rr.reconciliation_run_id
                              INNER JOIN fgc.validation_run vr
                                  ON vr.validation_run_id = rrn.validation_run_id
                             WHERE rr.reconciliation_run_id = :reconciliationRunId
                               AND rr.result_type <> 'MATCHED'
                        )
                        SELECT COUNT(*) AS candidateCount,
                               COUNT(*) FILTER (WHERE recorded.new_case) AS createdCount
                          FROM candidates c
                          CROSS JOIN LATERAL fgc.record_exception_detection(
                              CONCAT('RECONCILIATION_MISMATCH:', TO_CHAR(c.validation_month, 'YYYY-MM'),
                                     ':CONTRACT:', c.contract_id, ':', c.payment_stage, ':',
                                     LEFT(c.match_group_key, 140), ':', MD5(c.match_group_key), ':',
                                     COALESCE(c.primary_reason_code, c.result_type, 'RECONCILIATION_MISMATCH')),
                              'RECONCILIATION_MISMATCH',
                              COALESCE(c.primary_reason_code, c.result_type, 'RECONCILIATION_MISMATCH'),
                              CASE c.result_type WHEN 'REVIEW_REQUIRED' THEN 'WARNING' ELSE 'HIGH' END,
                              c.validation_run_id, c.contract_id,
                              COALESCE(c.actual_agent_id, c.expected_agent_id), c.tolerance_policy_version_id,
                              'RECONCILIATION_RESULT', CAST(c.reconciliation_result_id AS varchar),
                              CASE COALESCE(c.primary_reason_code, c.result_type)
                                  WHEN 'ACTUAL_MISSING' THEN CONCAT(
                                      '실제 지급 없음 — 예상 ',
                                      TO_CHAR(COALESCE(c.expected_total_amount, 0), 'FM999,999,999,990'),
                                      '원 지급 건 미발견')
                                  WHEN 'EXPECTED_MISSING' THEN CONCAT(
                                      '예상 지급 없음 — 실제 ',
                                      TO_CHAR(COALESCE(c.actual_total_amount, 0), 'FM999,999,999,990'),
                                      '원 대응 예상 건 없음')
                                  WHEN 'AMOUNT_DIFFERENCE' THEN CONCAT(
                                      '금액 차이 ', TO_CHAR(ABS(COALESCE(c.difference_amount, 0)), 'FM999,999,999,990'),
                                      '원 — 예상 ', TO_CHAR(COALESCE(c.expected_total_amount, 0), 'FM999,999,999,990'),
                                      ' / 실제 ', TO_CHAR(COALESCE(c.actual_total_amount, 0), 'FM999,999,999,990'))
                                  WHEN 'DUPLICATE' THEN CONCAT(
                                      '대사 대상 중복 — 차액 ',
                                      TO_CHAR(COALESCE(c.difference_amount, 0), 'FM999,999,999,990'), '원')
                                  ELSE CONCAT(
                                      '대사 불일치 — 차액 ',
                                      TO_CHAR(COALESCE(c.difference_amount, 0), 'FM999,999,999,990'), '원')
                              END,
                              CONCAT(
                                  '지급단계 ', c.payment_stage,
                                  ' · 예상 ', TO_CHAR(COALESCE(c.expected_total_amount, 0), 'FM999,999,999,990'), '원',
                                  ' / 실제 ', TO_CHAR(COALESCE(c.actual_total_amount, 0), 'FM999,999,999,990'), '원',
                                  ' · 차액 ', TO_CHAR(COALESCE(c.difference_amount, 0), 'FM999,999,999,990'), '원'
                              ),
                              jsonb_build_object(
                                  'reconciliationResultId', c.reconciliation_result_id,
                                  'paymentStage', c.payment_stage,
                                  'matchGroupKey', c.match_group_key,
                                  'resultType', c.result_type,
                                  'primaryReasonCode', c.primary_reason_code,
                                  'secondaryReasonCodes', c.secondary_reason_codes,
                                  'expectedTotalAmount', c.expected_total_amount,
                                  'actualTotalAmount', c.actual_total_amount,
                                  'differenceAmount', c.difference_amount
                              )
                          ) recorded
                """)
                .setParameter("reconciliationRunId", reconciliationRunId)
                .getSingleResult();
        entityManager.clear();
        ReconciliationExceptionBulkCreateRow result = new ReconciliationExceptionBulkCreateRow();
        result.setCandidateCount(((Number) counts[0]).longValue());
        result.setCreatedCount(((Number) counts[1]).longValue());
        return result;
    }
}
