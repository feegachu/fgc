package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 대사 실행 이력의 필터·정렬·페이징과 결과 집계 조회.
 * PostgreSQL 원천·집계·잠금 계약을 보존하며 타입 지정 projection으로 조회한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationRunHistoryRepository {
    private final EntityManager entityManager;

    public List<ReconciliationRunHistoryRow> search(LocalDate settlementMonth, String paymentStage, Long insurerId, String sortDirection, int offset, int limit) {
        // 검색값은 모두 바인딩하며 정렬 키워드는 고정된 ASC/DESC 중에서만 선택한다.
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT rr.reconciliation_run_id, rr.validation_run_id, rr.settlement_month, rr.payment_stage,
                rr.insurer_id, ins.insurer_name AS insurerName,
                rr.status, rr.tolerance_policy_version_id,
                creator.login_id AS createdBy, rr.created_at AS createdAt,
                rr.started_at AS startedAt, rr.completed_at AS completedAt,
                rr.finalized_at AS finalizedAt, finalizer.login_id AS finalizedBy,
                vw.result_count AS targetCount, vw.matched_count AS matchedCount,
                vw.exception_count AS exceptionCount,
                vw.expected_total AS expectedTotal, vw.actual_total AS actualTotal,
                vw.difference_total AS differenceTotal
                FROM fgc.reconciliation_run rr
                JOIN fgc.vw_reconciliation_summary vw ON vw.reconciliation_run_id = rr.reconciliation_run_id
                LEFT JOIN fgc.insurer ins ON ins.insurer_id = rr.insurer_id
                LEFT JOIN fgc.app_user creator ON creator.user_id = rr.created_by
                LEFT JOIN fgc.app_user finalizer ON finalizer.user_id = rr.finalized_by
                WHERE 1 = 1
                AND (CAST(:settlementMonth AS date) IS NULL OR rr.settlement_month = :settlementMonth)
                AND (CAST(:paymentStage AS varchar) IS NULL OR rr.payment_stage = :paymentStage)
                AND (CAST(:insurerId AS bigint) IS NULL OR rr.insurer_id = :insurerId)
                ORDER BY CASE WHEN :ascending THEN rr.created_at END ASC,
                         CASE WHEN NOT :ascending THEN rr.created_at END DESC,
                         CASE WHEN :ascending THEN rr.reconciliation_run_id END ASC,
                         CASE WHEN NOT :ascending THEN rr.reconciliation_run_id END DESC
                """).unwrap(NativeQuery.class);
        query.setParameter("ascending", "asc".equals(sortDirection));
        query.setParameter("settlementMonth", settlementMonth);
        query.setParameter("paymentStage", paymentStage);
        query.setParameter("insurerId", insurerId);
        query.setFirstResult(offset).setMaxResults(limit);
        query.addScalar("reconciliation_run_id", Long.class);
        query.addScalar("validation_run_id", Long.class);
        query.addScalar("settlement_month", LocalDate.class);
        query.addScalar("payment_stage", String.class);
        query.addScalar("insurer_id", Long.class);
        query.addScalar("insurername", String.class);
        query.addScalar("status", String.class);
        query.addScalar("tolerance_policy_version_id", Long.class);
        query.addScalar("createdby", String.class);
        query.addScalar("createdat", OffsetDateTime.class);
        query.addScalar("startedat", OffsetDateTime.class);
        query.addScalar("completedat", OffsetDateTime.class);
        query.addScalar("finalizedat", OffsetDateTime.class);
        query.addScalar("finalizedby", String.class);
        query.addScalar("targetcount", Long.class);
        query.addScalar("matchedcount", Long.class);
        query.addScalar("exceptioncount", Long.class);
        query.addScalar("expectedtotal", BigDecimal.class);
        query.addScalar("actualtotal", BigDecimal.class);
        query.addScalar("differencetotal", BigDecimal.class);
        return query.setTupleTransformer((values, aliases) -> {
            ReconciliationRunHistoryRow row = new ReconciliationRunHistoryRow();
            row.setReconciliationRunId((Long) values[0]);
            row.setValidationRunId((Long) values[1]);
            row.setSettlementMonth((LocalDate) values[2]);
            row.setPaymentStage((String) values[3]);
            row.setInsurerId((Long) values[4]);
            row.setInsurerName((String) values[5]);
            row.setStatus((String) values[6]);
            row.setTolerancePolicyVersionId((Long) values[7]);
            row.setCreatedBy((String) values[8]);
            row.setCreatedAt((OffsetDateTime) values[9]);
            row.setStartedAt((OffsetDateTime) values[10]);
            row.setCompletedAt((OffsetDateTime) values[11]);
            row.setFinalizedAt((OffsetDateTime) values[12]);
            row.setFinalizedBy((String) values[13]);
            row.setTargetCount((Long) values[14]);
            row.setMatchedCount((Long) values[15]);
            row.setExceptionCount((Long) values[16]);
            row.setExpectedTotal((BigDecimal) values[17]);
            row.setActualTotal((BigDecimal) values[18]);
            row.setDifferenceTotal((BigDecimal) values[19]);
            return row;
        }).getResultList();
    }

    public long count(LocalDate settlementMonth, String paymentStage, Long insurerId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT COUNT(*)
                FROM fgc.reconciliation_run rr
                WHERE 1 = 1
                AND (CAST(:settlementMonth AS date) IS NULL OR rr.settlement_month = :settlementMonth)
                AND (CAST(:paymentStage AS varchar) IS NULL OR rr.payment_stage = :paymentStage)
                AND (CAST(:insurerId AS bigint) IS NULL OR rr.insurer_id = :insurerId)
                """).unwrap(NativeQuery.class);
        query.setParameter("settlementMonth", settlementMonth);
        query.setParameter("paymentStage", paymentStage);
        query.setParameter("insurerId", insurerId);
        return ((Number) query.getSingleResult()).longValue();
    }
}
