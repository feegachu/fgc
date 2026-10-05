package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionAssigneeRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchDTO;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionOccurrenceRow;
import com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : IF-API-43 예외 검색·요약·선택지·이력을 DTO로 조회한다.
 * 공용 예외·사용자 매핑은 JPQL로 조회하며, PostgreSQL ILIKE와 다른 영역의
 * 대사 결과·발생 이력 연결은 계약 조회 Repository와 같은 네이티브 투영을 사용한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-05
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExceptionCaseQueryRepository {

    private static final String FROM_SQL = """
            FROM fgc.exception_case ec
            LEFT JOIN fgc.insurance_contract ic ON ic.contract_id = ec.contract_id
            """;

    private static final String SELECT_SQL = """
            SELECT ec.exception_case_id, ec.exception_key, ec.exception_type, ec.reason_code,
                   ec.severity, ec.status, ec.title, ec.description, ec.contract_id,
                   ic.contract_no, ag.agent_name, ec.assigned_to,
                   assignee.login_id AS assignee_login_id,
                   ec.source_entity_type, ec.source_entity_id, ec.cap_check_id,
                   reconciliation.result_type AS reconciliation_result_type,
                   ec.validation_month, ec.first_detected_run_id, ec.last_detected_run_id,
                   ec.first_detected_at, ec.last_detected_at, ec.detection_count, ec.created_at
            """;

    private static final String DISPLAY_JOINS = """
            LEFT JOIN fgc.agent ag ON ag.agent_id = ec.agent_id
            LEFT JOIN fgc.app_user assignee ON assignee.user_id = ec.assigned_to
            LEFT JOIN fgc.reconciliation_result reconciliation
                   ON ec.source_entity_type = 'RECONCILIATION_RESULT'
                  AND ec.source_entity_id = CAST(reconciliation.reconciliation_result_id AS varchar)
            """;

    private static final String ORDER_BY = """
             ORDER BY CASE ec.severity
                        WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                        WHEN 'WARNING' THEN 2 ELSE 3 END,
                      ec.created_at DESC, ec.exception_case_id DESC
            """;

    private final EntityManager entityManager;

    /** count로 보정한 offset만 받아 현재 페이지를 조회한다. */
    // S2077: 코드에 정의한 조건식만 조립하고 사용자 입력은 모두 setParameter로 바인딩한다.
    @SuppressWarnings("java:S2077")
    public List<ExceptionCaseSearchRow> search(
            ExceptionCaseSearchDTO criteria, List<ExceptionStatus> statuses, int offset, int limit) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        NativeQuery<?> query = nativeQuery(SELECT_SQL + FROM_SQL + DISPLAY_JOINS
                + buildWhere(criteria, statuses, parameters) + ORDER_BY);
        parameters.forEach(query::setParameter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        query.addScalar("exception_case_id", Long.class);
        query.addScalar("exception_key", String.class);
        query.addScalar("exception_type", String.class);
        query.addScalar("reason_code", String.class);
        query.addScalar("severity", String.class);
        query.addScalar("status", String.class);
        query.addScalar("title", String.class);
        query.addScalar("description", String.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("contract_no", String.class);
        query.addScalar("agent_name", String.class);
        query.addScalar("assigned_to", Long.class);
        query.addScalar("assignee_login_id", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        query.addScalar("cap_check_id", Long.class);
        query.addScalar("reconciliation_result_type", String.class);
        query.addScalar("validation_month", LocalDate.class);
        query.addScalar("first_detected_run_id", Long.class);
        query.addScalar("last_detected_run_id", Long.class);
        query.addScalar("first_detected_at", OffsetDateTime.class);
        query.addScalar("last_detected_at", OffsetDateTime.class);
        query.addScalar("detection_count", Integer.class);
        query.addScalar("created_at", OffsetDateTime.class);
        return query.setTupleTransformer((row, aliases) -> new ExceptionCaseSearchRow(
                (Long) row[0], (String) row[1], (String) row[2], (String) row[3],
                (String) row[4], (String) row[5], (String) row[6], (String) row[7],
                (Long) row[8], (String) row[9], (String) row[10], (Long) row[11],
                (String) row[12], (String) row[13], (String) row[14], (Long) row[15],
                (String) row[16], (LocalDate) row[17], (Long) row[18], (Long) row[19],
                (OffsetDateTime) row[20], (OffsetDateTime) row[21], (Integer) row[22],
                (OffsetDateTime) row[23])).getResultList();
    }

    /** 목록과 동일한 필터로 먼저 전체 건수를 구한다. */
    @SuppressWarnings("java:S2077")
    public long count(ExceptionCaseSearchDTO criteria, List<ExceptionStatus> statuses) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        Query query = entityManager.createNativeQuery("SELECT COUNT(*) " + FROM_SQL
                + buildWhere(criteria, statuses, parameters));
        parameters.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** 부모별 조회를 반복하지 않고 처리자 표시값까지 한 번에 반환한다. */
    public List<ExceptionActionRow> findActionsByCaseIds(List<Long> exceptionCaseIds) {
        if (exceptionCaseIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.exceptioncase.dto.ExceptionActionRow(
                    a.exceptionActionId, a.exceptionCaseId, a.actionSeq,
                    cast(a.fromStatus as String), cast(a.toStatus as String),
                    cast(a.actionType as String), a.reason, a.evidenceRef,
                    a.actionBy, actor.loginId, a.actionAt)
                  FROM ExceptionAction a JOIN AppUser actor ON actor.userId = a.actionBy
                 WHERE a.exceptionCaseId IN :exceptionCaseIds
                 ORDER BY a.exceptionCaseId, a.actionSeq
                """, ExceptionActionRow.class)
                .setParameter("exceptionCaseIds", exceptionCaseIds)
                .getResultList();
    }

    /** E 소유 발생 이력·검증 실행의 중복 엔티티 없이 JSONB의 기존 text 표현을 유지한다. */
    public List<ExceptionOccurrenceRow> findOccurrencesByCaseIds(List<Long> exceptionCaseIds) {
        if (exceptionCaseIds.isEmpty()) {
            return List.of();
        }
        NativeQuery<?> query = nativeQuery("""
                SELECT o.exception_occurrence_id, o.exception_case_id, o.validation_run_id,
                       vr.run_no, vr.validation_month, o.exception_type, o.reason_code,
                       o.source_entity_type, o.source_entity_id,
                       CAST(o.evidence_snapshot AS text) AS evidence_json,
                       o.is_new_case, o.was_reopened, o.detected_at
                  FROM fgc.exception_occurrence o
                  JOIN fgc.validation_run vr ON vr.validation_run_id = o.validation_run_id
                 WHERE o.exception_case_id IN (:exceptionCaseIds)
                 ORDER BY o.exception_case_id, o.detected_at DESC, o.exception_occurrence_id DESC
                """);
        query.setParameter("exceptionCaseIds", exceptionCaseIds);
        query.addScalar("exception_occurrence_id", Long.class);
        query.addScalar("exception_case_id", Long.class);
        query.addScalar("validation_run_id", Long.class);
        query.addScalar("run_no", Integer.class);
        query.addScalar("validation_month", LocalDate.class);
        query.addScalar("exception_type", String.class);
        query.addScalar("reason_code", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        query.addScalar("evidence_json", String.class);
        query.addScalar("is_new_case", Boolean.class);
        query.addScalar("was_reopened", Boolean.class);
        query.addScalar("detected_at", OffsetDateTime.class);
        return query.setTupleTransformer((row, aliases) -> new ExceptionOccurrenceRow(
                (Long) row[0], (Long) row[1], (Long) row[2], (Integer) row[3],
                (LocalDate) row[4], (String) row[5], (String) row[6], (String) row[7],
                (String) row[8], (String) row[9], (Boolean) row[10], (Boolean) row[11],
                (OffsetDateTime) row[12])).getResultList();
    }

    /** 검색 조건과 독립적인 전체 미처리 요약이다. */
    public List<ExceptionTypeSummaryRow> countOpenByType() {
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryRow(
                    cast(e.exceptionType as String), count(e))
                  FROM ExceptionCase e
                 WHERE e.status IN :statuses
                 GROUP BY e.exceptionType ORDER BY e.exceptionType
                """, ExceptionTypeSummaryRow.class)
                .setParameter("statuses", ExceptionStatus.dbStatuses(ExceptionStatus.OPEN_FILTER))
                .getResultList();
    }

    public List<String> findReasonCodes() {
        return entityManager.createQuery("""
                SELECT DISTINCT e.reasonCode FROM ExceptionCase e
                 WHERE e.reasonCode IS NOT NULL ORDER BY e.reasonCode
                """, String.class).getResultList();
    }

    public List<ExceptionAssigneeRow> findAssignees() {
        return entityManager.createQuery("""
                SELECT DISTINCT new com.susukkang.fgc.exceptioncase.dto.ExceptionAssigneeRow(
                    e.assignedTo, u.loginId)
                  FROM ExceptionCase e JOIN AppUser u ON u.userId = e.assignedTo
                 ORDER BY u.loginId
                """, ExceptionAssigneeRow.class).getResultList();
    }

    public List<LocalDate> findValidationMonths() {
        return entityManager.createQuery("""
                SELECT DISTINCT e.validationMonth FROM ExceptionCase e
                 WHERE e.validationMonth IS NOT NULL ORDER BY e.validationMonth DESC
                """, LocalDate.class).getResultList();
    }

    // 조건식만 조립하며 모든 입력값은 바인딩한다. 목록·count의 필터가 어긋나지 않게 공유한다.
    private String buildWhere(ExceptionCaseSearchDTO criteria, List<ExceptionStatus> statuses,
                              Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        append(where, parameters, "ec.exception_type = :type", "type", criteria.getType());
        if (criteria.getReasonCode() != null && !criteria.getReasonCode().isBlank()) {
            append(where, parameters, "ec.reason_code = :reasonCode", "reasonCode", criteria.getReasonCode());
        }
        append(where, parameters, "ec.severity = :severity", "severity", criteria.getSeverity());
        append(where, parameters, "ec.validation_run_id = :validationRunId", "validationRunId",
                criteria.getValidationRunId());
        if (criteria.getTypes() != null && !criteria.getTypes().isEmpty()) {
            append(where, parameters, "ec.exception_type IN (:types)", "types",
                    criteria.getTypes().stream().map(Enum::name).toList());
        }
        if (statuses != null && !statuses.isEmpty()) {
            append(where, parameters, "ec.status IN (:statuses)", "statuses",
                    statuses.stream().map(Enum::name).toList());
        }
        if (criteria.isUnassignedOnly()) {
            where.append(" AND ec.assigned_to IS NULL");
        } else {
            append(where, parameters, "ec.assigned_to = :assignee", "assignee", criteria.getAssignee());
        }
        if (criteria.getContractNo() != null && !criteria.getContractNo().isBlank()) {
            append(where, parameters, "ic.contract_no ILIKE CONCAT('%', :contractNo, '%')", "contractNo",
                    criteria.getContractNo());
        }
        append(where, parameters, "ec.validation_month = :validationMonth", "validationMonth",
                criteria.getValidationMonth());
        return where.toString();
    }

    private void append(StringBuilder where, Map<String, Object> parameters,
                        String expression, String name, Object value) {
        if (value != null) {
            where.append(" AND ").append(expression);
            parameters.put(name, value instanceof Enum<?> enumValue ? enumValue.name() : value);
        }
    }

    private NativeQuery<?> nativeQuery(String sql) {
        return entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
    }
}
