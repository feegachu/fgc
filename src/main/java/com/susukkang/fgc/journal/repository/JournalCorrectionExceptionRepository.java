package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import com.susukkang.fgc.exceptioncase.entity.ExceptionAction;
import com.susukkang.fgc.exceptioncase.repository.ExceptionActionRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionInsertCommand;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 원장 정정 예외의 활성 요청 조회와 멱등 생성을 담당한다.
 * 공용 예외 엔티티로 조회·이력을 처리하며, ON CONFLICT 멱등 INSERT는 네이티브 SQL로 유지한다.
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
    private final ExceptionCaseRepository exceptionCaseRepository;
    private final ExceptionActionRepository exceptionActionRepository;

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
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow(e.exceptionCaseId, e.status)
                  FROM ExceptionCase e
                 WHERE e.sourceEntityType = 'JOURNAL_HEADER'
                   AND e.sourceEntityId = :sourceId
                   AND e.exceptionType = :exceptionType
                   AND (e.policyVersionId = :policyVersionId
                        OR (e.policyVersionId IS NULL AND :policyVersionId IS NULL))
                   AND e.status IN (:newStatus, :reviewStatus)
                 ORDER BY e.exceptionCaseId DESC
                """, JournalCorrectionExceptionRow.class)
                .setParameter("sourceId", String.valueOf(journalHeaderId))
                .setParameter("exceptionType", ExceptionType.JOURNAL_CORRECTION_REQUIRED)
                .setParameter("policyVersionId", policyVersionId)
                .setParameter("newStatus", ExceptionStatus.NEW)
                .setParameter("reviewStatus", ExceptionStatus.IN_REVIEW)
                .setMaxResults(1)
                .getResultList().stream().findFirst().orElse(null);
    }

    public int countBySource(Long journalHeaderId, Long policyVersionId) {
        return entityManager.createQuery("""
                SELECT COUNT(e)
                  FROM ExceptionCase e
                 WHERE e.sourceEntityType = 'JOURNAL_HEADER'
                   AND e.sourceEntityId = :sourceId
                   AND e.exceptionType = :exceptionType
                   AND (e.policyVersionId = :policyVersionId
                        OR (e.policyVersionId IS NULL AND :policyVersionId IS NULL))
                """, Long.class)
                .setParameter("sourceId", String.valueOf(journalHeaderId))
                .setParameter("exceptionType", ExceptionType.JOURNAL_CORRECTION_REQUIRED)
                .setParameter("policyVersionId", policyVersionId)
                .getSingleResult().intValue();
    }

    public JournalCorrectionExceptionRow findByExceptionKey(String exceptionKey) {
        return entityManager.createQuery("""
                SELECT new com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRow(e.exceptionCaseId, e.status)
                  FROM ExceptionCase e WHERE e.exceptionKey = :exceptionKey
                """, JournalCorrectionExceptionRow.class)
                .setParameter("exceptionKey", exceptionKey)
                .getResultList().stream().findFirst().orElse(null);
    }

    @Transactional
    public void insertInitialAction(Long exceptionCaseId, JournalCorrectionExceptionInsertCommand command) {
        // 앞서 조회한 업무건 ID를 재사용하여 추가 SELECT 없이 공용 엔티티를 저장한다.
        // actionAt은 생략하여 기존 초기 이력의 DB clock_timestamp() 기본값을 유지한다.
        exceptionActionRepository.save(ExceptionAction.builder()
                .exceptionCaseId(exceptionCaseId)
                .actionSeq(1)
                .fromStatus(ExceptionStatus.NEW)
                .toStatus(ExceptionStatus.NEW)
                .actionType(ExceptionActionType.COMMENT)
                .reason(command.reason())
                .evidenceRef(command.evidenceRef())
                .actionBy(command.requestedBy())
                .build());
    }

    @Transactional
    public JournalCorrectionExceptionTarget findJournalCorrectionTargetForUpdate(Long exceptionCaseId) {
        return exceptionCaseRepository.findJournalCorrectionTargetForUpdate(exceptionCaseId);
    }
}
