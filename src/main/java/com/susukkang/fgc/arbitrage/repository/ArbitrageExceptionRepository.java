package com.susukkang.fgc.arbitrage.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * C 소유 차익거래 호출부의 검출 어댑터. 공용 예외 엔티티를 중복 정의하지 않는다.
 * #380의 공유 JPA 검출 API가 병합되기 전까지 기존 DB 함수를 호출하며,
 * 업무키·재검출·재개방·실행별 검출 이력 계약은 fgc.record_exception_detection이 관리한다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ArbitrageExceptionRepository {
    private final EntityManager entityManager;

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
}
