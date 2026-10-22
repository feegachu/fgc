package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.SqlSort;
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
import java.util.Map;
import java.util.Set;

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
    private static final String CREATED_AT = "createdAt";
    private static final String SETTLEMENT_MONTH = "settlementMonth";

    private final EntityManager entityManager;

    /** 화면 정렬 key(RECO-W01 실행 이력) → SQL 표현식. 지급단계·상태는 순서 기준이 없어 제외한다. */
    private static final Map<String, String> SORTABLE = Map.of(
            CREATED_AT, "rr.created_at",
            "reconciliationRunId", "rr.reconciliation_run_id",
            SETTLEMENT_MONTH, "rr.settlement_month",
            "insurerName", "ins.insurer_name",
            "targetCount", "vw.result_count",
            "matchedCount", "vw.matched_count",
            "exceptionCount", "vw.exception_count",
            "matchRatePct", "(vw.matched_count::numeric / NULLIF(vw.result_count, 0))",
            "differenceTotal", "vw.difference_total",
            "startedAt", "rr.started_at");
    public static final Set<String> SORT_KEYS = Set.copyOf(SORTABLE.keySet());
    public static final ListSort DEFAULT_SORT = new ListSort(CREATED_AT, false);

    /** 기존 호출 호환: 실행 시각 기준 방향만 고른다. */
    public List<ReconciliationRunHistoryRow> search(LocalDate settlementMonth, String paymentStage, Long insurerId, String sortDirection, int offset, int limit) {
        return search(settlementMonth, paymentStage, insurerId, new ListSort(CREATED_AT, "asc".equals(sortDirection)), offset, limit);
    }

    public List<ReconciliationRunHistoryRow> search(LocalDate settlementMonth, String paymentStage, Long insurerId, ListSort sort, int offset, int limit) {
        // 검색값은 모두 바인딩하며 정렬은 허용 목록(SORTABLE)의 코드 상수 표현식만 쓴다. 동률은 실행 ID가 방향을 따른다.
        String orderBy = SqlSort.orderBy(sort, SORTABLE, "rr.reconciliation_run_id " + (sort.ascending() ? "ASC" : "DESC"));
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
                vw.difference_total AS differenceTotal, rr.superseded_yn, rr.corrects_reconciliation_run_id
                FROM fgc.reconciliation_run rr
                JOIN fgc.vw_reconciliation_summary vw ON vw.reconciliation_run_id = rr.reconciliation_run_id
                LEFT JOIN fgc.insurer ins ON ins.insurer_id = rr.insurer_id
                LEFT JOIN fgc.app_user creator ON creator.user_id = rr.created_by
                LEFT JOIN fgc.app_user finalizer ON finalizer.user_id = rr.finalized_by
                WHERE 1 = 1
                AND (CAST(:settlementMonth AS date) IS NULL OR rr.settlement_month = :settlementMonth)
                AND (CAST(:paymentStage AS varchar) IS NULL OR rr.payment_stage = :paymentStage)
                AND (CAST(:insurerId AS bigint) IS NULL OR rr.insurer_id = :insurerId)
                __order__
                """.replace("__order__", orderBy)).unwrap(NativeQuery.class);
        query.setParameter(SETTLEMENT_MONTH, settlementMonth);
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
        query.addScalar("superseded_yn", Boolean.class);
        query.addScalar("corrects_reconciliation_run_id", Long.class);
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
            row.setSuperseded((Boolean) values[20]);
            row.setCorrectsReconciliationRunId((Long) values[21]);
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
        query.setParameter(SETTLEMENT_MONTH, settlementMonth);
        query.setParameter("paymentStage", paymentStage);
        query.setParameter("insurerId", insurerId);
        return ((Number) query.getSingleResult()).longValue();
    }
}
