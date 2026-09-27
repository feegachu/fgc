package com.susukkang.fgc.dashboard.repository;

import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 대시보드 카드 집계와 최근 목록을 네이티브 SQL로 조회한다.
 * PostgreSQL의 DISTINCT ON과 기존 월 필터·정렬 조건을 유지하며 DTO로 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardQueryRepository {

    private final EntityManager entityManager;

    public long countCapViolation(LocalDate month) {
        return countCapByStatus(month, "VIOLATION");
    }

    public long countCapWarning(LocalDate month) {
        return countCapByStatus(month, "WARNING");
    }

    private long countCapByStatus(LocalDate month, String resultStatus) {
        // 월을 먼저 제한한 뒤 계약·지급단계별 최신 판정을 선택한다.
        // timestamp 캐스팅은 기존 월별 표현식 인덱스와 같은 타입을 유지한다.
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                  FROM (
                      SELECT DISTINCT ON (contract_id, payment_stage) result_status
                        FROM fgc.cap_check
                       WHERE date_trunc('month', CAST(as_of_date AS timestamp))
                             = date_trunc('month', CAST(CAST(:month AS date) AS timestamp))
                       ORDER BY contract_id, payment_stage, checked_at DESC, cap_check_id DESC
                  ) AS latest_in_month
                 WHERE result_status = :resultStatus
                """)
                .setParameter("month", month)
                .setParameter("resultStatus", resultStatus)
                .getSingleResult()).longValue();
    }

    public long countArbitrageCandidate(LocalDate month) {
        // 월 내 재실행은 계약·지급단계별 최신 결과 한 건만 집계한다.
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                  FROM (
                      SELECT DISTINCT ON (contract_id, payment_stage) result_status
                        FROM fgc.arbitrage_check
                       WHERE date_trunc('month', CAST(as_of_date AS timestamp))
                             = date_trunc('month', CAST(CAST(:month AS date) AS timestamp))
                       ORDER BY contract_id, payment_stage, as_of_date DESC, arbitrage_check_id DESC
                  ) AS latest_in_month
                 WHERE result_status = 'CANDIDATE'
                """)
                .setParameter("month", month)
                .getSingleResult()).longValue();
    }

    public long countReconciliationMismatch(LocalDate month) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                  FROM fgc.reconciliation_result
                  JOIN (
                      SELECT DISTINCT ON (settlement_month, payment_stage, insurer_id) reconciliation_run_id
                        FROM fgc.reconciliation_run
                       WHERE date_trunc('month', settlement_month) = date_trunc('month', CAST(:month AS date))
                       ORDER BY settlement_month, payment_stage, insurer_id, created_at DESC, reconciliation_run_id DESC
                  ) AS latest_run ON latest_run.reconciliation_run_id = reconciliation_result.reconciliation_run_id
                 WHERE reconciliation_result.result_type <> 'MATCHED'
                """)
                .setParameter("month", month)
                .getSingleResult()).longValue();
    }

    public long countJournalImbalance() {
        // 원장 불균형은 조회 월과 무관하게 전체를 집계한다.
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                  FROM fgc.vw_journal_imbalance
                """)
                .getSingleResult()).longValue();
    }

    public long countOpenException() {
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                  FROM fgc.exception_case
                 WHERE status IN ('NEW', 'IN_REVIEW')
                """)
                .getSingleResult()).longValue();
    }

    public List<RecentExceptionRow> findRecentExceptions(int limit) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT ec.exception_case_id,
                       ec.exception_type,
                       ec.severity,
                       ic.contract_no,
                       ec.title,
                       ec.status,
                       ec.created_at
                  FROM fgc.exception_case ec
                  LEFT JOIN fgc.insurance_contract ic ON ic.contract_id = ec.contract_id
                 ORDER BY ec.created_at DESC, ec.exception_case_id DESC
                 LIMIT :limit
                """).unwrap(NativeQuery.class);
        query.setParameter("limit", limit);

        query.addScalar("exception_case_id", Long.class);
        query.addScalar("exception_type", String.class);
        query.addScalar("severity", String.class);
        query.addScalar("contract_no", String.class);
        query.addScalar("title", String.class);
        query.addScalar("status", String.class);
        query.addScalar("created_at", OffsetDateTime.class);

        return query.setTupleTransformer((row, aliases) -> new RecentExceptionRow(
                (Long) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (OffsetDateTime) row[6]
        )).getResultList();
    }

    public List<RecentValidationRunRow> findRecentValidationRuns(int limit) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT vr.validation_run_id,
                       vr.validation_month,
                       vr.run_no,
                       vr.run_type,
                       vr.status,
                       vr.current_step,
                       triggered.login_id AS triggered_by,
                       vr.started_at,
                       vr.completed_at,
                       vr.finalized_at,
                       finalized.login_id AS finalized_by,
                       vr.failure_message
                  FROM fgc.validation_run vr
                  LEFT JOIN fgc.app_user triggered ON triggered.user_id = vr.triggered_by
                  LEFT JOIN fgc.app_user finalized ON finalized.user_id = vr.finalized_by
                 WHERE vr.run_type = 'MONTHLY'
                 ORDER BY vr.created_at DESC, vr.validation_run_id DESC
                 LIMIT :limit
                """).unwrap(NativeQuery.class);
        query.setParameter("limit", limit);

        query.addScalar("validation_run_id", Long.class);
        query.addScalar("validation_month", LocalDate.class);
        query.addScalar("run_no", Integer.class);
        query.addScalar("run_type", String.class);
        query.addScalar("status", String.class);
        query.addScalar("current_step", Integer.class);
        query.addScalar("triggered_by", String.class);
        query.addScalar("started_at", OffsetDateTime.class);
        query.addScalar("completed_at", OffsetDateTime.class);
        query.addScalar("finalized_at", OffsetDateTime.class);
        query.addScalar("finalized_by", String.class);
        query.addScalar("failure_message", String.class);

        return query.setTupleTransformer((row, aliases) -> new RecentValidationRunRow(
                (Long) row[0],
                (LocalDate) row[1],
                (Integer) row[2],
                (String) row[3],
                (String) row[4],
                (Integer) row[5],
                (String) row[6],
                (OffsetDateTime) row[7],
                (OffsetDateTime) row[8],
                (OffsetDateTime) row[9],
                (String) row[10],
                (String) row[11]
        )).getResultList();
    }
}
