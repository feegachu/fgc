package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionInsertCommand;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 원장 정정 예외의 활성 요청 조회와 멱등 생성을 담당한다.
 * 예외 도메인에 엔티티가 없어 네이티브 SQL로 업무건과 최초 처리 이력을 저장한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalCorrectionExceptionRepository {

    private final EntityManager entityManager;

    @Transactional
    public int insertCase(JournalCorrectionExceptionInsertCommand command) {
        // 원분개 잠금 안에서 호출하며, 동일 요청 키가 이미 있으면 기존 업무건을 재사용한다.
        return entityManager.createNativeQuery("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, reason_code, severity, status,
                    validation_run_id, validation_month, contract_id, policy_version_id,
                    source_entity_type, source_entity_id, title, description
                ) VALUES (
                    :exceptionKey, 'JOURNAL_CORRECTION_REQUIRED', 'JOURNAL_CORRECTION_REQUIRED',
                    'HIGH', 'NEW', :validationRunId, :validationMonth, :contractId,
                    :policyVersionId, 'JOURNAL_HEADER', CAST(:journalHeaderId AS varchar),
                    :title, :description
                )
                ON CONFLICT ON CONSTRAINT uq_exception_key DO NOTHING
                """)
                .setParameter("exceptionKey", command.exceptionKey())
                .setParameter("validationRunId", command.validationRunId())
                .setParameter("validationMonth", command.validationMonth())
                .setParameter("contractId", command.contractId())
                .setParameter("policyVersionId", command.policyVersionId())
                .setParameter("journalHeaderId", command.journalHeaderId())
                .setParameter("title", command.title())
                .setParameter("description", command.description())
                .executeUpdate();
    }

    public JournalCorrectionExceptionRow findActiveBySource(Long journalHeaderId, Long policyVersionId) {
        NativeQuery<?> query = createRowQuery("""
                SELECT exception_case_id, status
                  FROM fgc.exception_case
                 WHERE source_entity_type = 'JOURNAL_HEADER'
                   AND source_entity_id = CAST(:journalHeaderId AS varchar)
                   AND exception_type = 'JOURNAL_CORRECTION_REQUIRED'
                   AND policy_version_id IS NOT DISTINCT FROM :policyVersionId
                   AND status IN ('NEW', 'IN_REVIEW')
                 ORDER BY exception_case_id DESC
                 LIMIT 1
                """);
        query.setParameter("journalHeaderId", journalHeaderId);
        query.setParameter("policyVersionId", policyVersionId);
        return findRow(query);
    }

    public int countBySource(Long journalHeaderId, Long policyVersionId) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*)
                  FROM fgc.exception_case
                 WHERE source_entity_type = 'JOURNAL_HEADER'
                   AND source_entity_id = CAST(:journalHeaderId AS varchar)
                   AND exception_type = 'JOURNAL_CORRECTION_REQUIRED'
                   AND policy_version_id IS NOT DISTINCT FROM :policyVersionId
                """)
                .setParameter("journalHeaderId", journalHeaderId)
                .setParameter("policyVersionId", policyVersionId)
                .getSingleResult()).intValue();
    }

    public JournalCorrectionExceptionRow findByExceptionKey(String exceptionKey) {
        NativeQuery<?> query = createRowQuery("""
                SELECT exception_case_id, status
                  FROM fgc.exception_case
                 WHERE exception_key = :exceptionKey
                """);
        query.setParameter("exceptionKey", exceptionKey);
        return findRow(query);
    }

    @Transactional
    public int insertInitialAction(JournalCorrectionExceptionInsertCommand command) {
        return entityManager.createNativeQuery("""
                INSERT INTO fgc.exception_action (
                    exception_case_id, action_seq, from_status, to_status,
                    action_type, reason, evidence_ref, action_by
                )
                SELECT exception_case_id, 1, 'NEW', 'NEW',
                       'COMMENT', :reason, :evidenceRef, :requestedBy
                  FROM fgc.exception_case
                 WHERE exception_key = :exceptionKey
                """)
                .setParameter("reason", command.reason())
                .setParameter("evidenceRef", command.evidenceRef())
                .setParameter("requestedBy", command.requestedBy())
                .setParameter("exceptionKey", command.exceptionKey())
                .executeUpdate();
    }

    @Transactional
    public JournalCorrectionExceptionTarget findJournalCorrectionTargetForUpdate(Long exceptionCaseId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT exception_case_id, exception_type, status, source_entity_type, source_entity_id
                  FROM fgc.exception_case
                 WHERE exception_case_id = :exceptionCaseId
                   FOR UPDATE
                """).unwrap(NativeQuery.class);
        query.setParameter("exceptionCaseId", exceptionCaseId);
        query.addScalar("exception_case_id", Long.class);
        query.addScalar("exception_type", String.class);
        query.addScalar("status", String.class);
        query.addScalar("source_entity_type", String.class);
        query.addScalar("source_entity_id", String.class);
        return query.setTupleTransformer((row, aliases) -> new JournalCorrectionExceptionTarget(
                        (Long) row[0], (String) row[1], ExceptionStatus.valueOf((String) row[2]),
                        (String) row[3], (String) row[4]))
                .getResultList().stream()
                .findFirst()
                .orElse(null);
    }

    private NativeQuery<?> createRowQuery(String sql) {
        NativeQuery<?> query = entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
        query.addScalar("exception_case_id", Long.class);
        query.addScalar("status", String.class);
        return query;
    }

    private JournalCorrectionExceptionRow findRow(NativeQuery<?> query) {
        return query.setTupleTransformer((row, aliases) -> new JournalCorrectionExceptionRow(
                        (Long) row[0], ExceptionStatus.valueOf((String) row[1])))
                .getResultList().stream()
                .findFirst()
                .orElse(null);
    }
}
