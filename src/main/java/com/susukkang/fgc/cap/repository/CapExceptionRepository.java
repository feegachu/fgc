package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import com.susukkang.fgc.exceptioncase.entity.ExceptionAction;
import com.susukkang.fgc.exceptioncase.repository.ExceptionActionRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.transaction.domain.ExceptionCaseCommand;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 지급·한도 예외의 멱등 생성과 공통 예외 저장소를 이용한 해결조치를 담당한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-30
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CapExceptionRepository {

    private final EntityManager entityManager;
    private final ExceptionCaseRepository exceptionCaseRepository;
    private final ExceptionActionRepository exceptionActionRepository;

    // 동일 업무키의 동시 생성과 종결 상태 보존은 PostgreSQL의 조건부 UPSERT로 처리한다.
    // 네이티브 변경 전 미반영 엔티티를 flush하고, 변경 후 재조회가 이전 상태를 재사용하지 않게 한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertException(CapExceptionInsertDTO command) {
        entityManager.flush();
        NativeQuery<?> query = entityManager.createNativeQuery("""
                INSERT INTO fgc.exception_case AS existing (
                    exception_key, exception_type, reason_code, severity, status,
                    validation_run_id, contract_id, agent_id, policy_version_id,
                    source_entity_type, source_entity_id, cap_check_id, validation_month,
                    title, description
                ) VALUES (
                    :exceptionKey, :exceptionType,
                    CASE WHEN :exceptionType = 'CAP_VIOLATION' THEN 'CAP_LIMIT_VIOLATION'
                         WHEN :exceptionType = 'CAP_WARNING' THEN 'CAP_LIMIT_WARNING'
                         ELSE :exceptionType END,
                    :severity, 'NEW', :validationRunId, :contractId, :agentId, :policyVersionId,
                    'COMMISSION_TRANSACTION', CAST(:paymentId AS varchar), :capCheckId,
                    (SELECT ct.settlement_month FROM fgc.commission_transaction ct
                      WHERE ct.commission_transaction_id = :paymentId),
                    :title, :description
                )
                ON CONFLICT (exception_key) DO UPDATE
                   SET exception_type = EXCLUDED.exception_type,
                       reason_code = EXCLUDED.reason_code,
                       severity = EXCLUDED.severity,
                       validation_run_id = COALESCE(EXCLUDED.validation_run_id, existing.validation_run_id),
                       contract_id = EXCLUDED.contract_id,
                       agent_id = EXCLUDED.agent_id,
                       cap_check_id = EXCLUDED.cap_check_id,
                       validation_month = COALESCE(EXCLUDED.validation_month, existing.validation_month),
                       title = EXCLUDED.title,
                       description = EXCLUDED.description,
                       updated_at = clock_timestamp()
                 WHERE existing.status IN ('NEW', 'IN_REVIEW')
                """).unwrap(NativeQuery.class);
        int affectedRows = query.setParameter("exceptionKey", command.getExceptionKey(), String.class)
                .setParameter("exceptionType", command.getExceptionType().name(), String.class)
                .setParameter("severity", command.getSeverity().name(), String.class)
                .setParameter("validationRunId", command.getValidationRunId(), Long.class)
                .setParameter("contractId", command.getContractId(), Long.class)
                .setParameter("agentId", command.getAgentId(), Long.class)
                .setParameter("policyVersionId", command.getPolicyVersionId(), Long.class)
                .setParameter("paymentId", command.getPaymentId(), Long.class)
                .setParameter("capCheckId", command.getCapCheckId(), Long.class)
                .setParameter("title", command.getTitle(), String.class)
                .setParameter("description", command.getDescription(), String.class)
                .executeUpdate();
        entityManager.clear();
        return affectedRows;
    }

    // 지급 확정의 정책 누락 등은 한도 예외와 갱신 규칙이 다르다. 기존 규칙을 별도로 보존한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public int insertExceptionCase(ExceptionCaseCommand command) {
        entityManager.flush();
        NativeQuery<?> query = entityManager.createNativeQuery("""
                INSERT INTO fgc.exception_case AS existing (
                    exception_key, exception_type, reason_code, severity, status,
                    contract_id, agent_id, policy_version_id, source_entity_type,
                    source_entity_id, validation_month, title, description
                ) VALUES (
                    :exceptionKey, :exceptionType, :reasonCode, :severity, 'NEW',
                    :contractId, :agentId, :policyVersionId, 'COMMISSION_TRANSACTION',
                    COALESCE(CAST(:paymentId AS varchar), :sourceEntityId),
                    (SELECT ct.settlement_month FROM fgc.commission_transaction ct
                      WHERE ct.commission_transaction_id = :paymentId),
                    :title, :description
                )
                ON CONFLICT (exception_key) DO UPDATE
                   SET status = 'NEW', reason_code = EXCLUDED.reason_code,
                       validation_month = COALESCE(EXCLUDED.validation_month, existing.validation_month),
                       title = EXCLUDED.title, description = EXCLUDED.description,
                       updated_at = clock_timestamp()
                 WHERE existing.status NOT IN ('RESOLVED', 'REJECTED')
                """).unwrap(NativeQuery.class);
        int affectedRows = query.setParameter("exceptionKey", command.getExceptionKey(), String.class)
                .setParameter("exceptionType", command.getExceptionType(), String.class)
                .setParameter("reasonCode", command.getReasonCode(), String.class)
                .setParameter("severity", command.getSeverity(), String.class)
                .setParameter("contractId", command.getContractId(), Long.class)
                .setParameter("agentId", command.getAgentId(), Long.class)
                .setParameter("policyVersionId", command.getPolicyVersionId(), Long.class)
                .setParameter("paymentId", command.getPaymentId(), Long.class)
                .setParameter("sourceEntityId", command.getSourceEntityId(), String.class)
                .setParameter("title", command.getTitle(), String.class)
                .setParameter("description", command.getDescription(), String.class)
                .executeUpdate();
        entityManager.clear();
        return affectedRows;
    }

    // DB 공통 검출 함수가 업무키·실행별 검출 이력·재검출 상태를 함께 관리한다.
    @Transactional(propagation = Propagation.MANDATORY)
    public Long recordCapCheckFailure(Long validationRunId, Long contractId,
                                      String paymentStage, String description) {
        entityManager.flush();
        NativeQuery<?> query = entityManager.createNativeQuery("""
                SELECT recorded.recorded_case_id
                  FROM fgc.validation_run vr
                  CROSS JOIN LATERAL fgc.record_exception_detection(
                      CONCAT('DATA_QUALITY:', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                             ':CONTRACT:', CAST(:contractId AS bigint), ':', CAST(:paymentStage AS varchar)),
                      'DATA_QUALITY', 'CAP_CALCULATION_FAILED', 'WARNING',
                      :validationRunId, :contractId, NULL, NULL,
                      'INSURANCE_CONTRACT', CAST(:contractId AS varchar),
                      CONCAT('1,200% 한도 검증 실패 - ', CAST(:paymentStage AS varchar)), :description,
                      jsonb_build_object('paymentStage', CAST(:paymentStage AS varchar),
                                         'description', CAST(:description AS varchar))
                  ) recorded
                 WHERE vr.validation_run_id = :validationRunId
                """, Long.class).unwrap(NativeQuery.class);
        List<?> recordedIds = query.setParameter("validationRunId", validationRunId, Long.class)
                .setParameter("contractId", contractId, Long.class)
                .setParameter("paymentStage", paymentStage, String.class)
                .setParameter("description", description, String.class)
                .getResultList();
        entityManager.clear();
        return recordedIds.isEmpty() ? null : ((Number) recordedIds.getFirst()).longValue();
    }

    // 공통 예외 잠금·상태 DTO를 재사용한다. 조회 이후 채번·조치·상태 갱신까지 같은 트랜잭션이다.
    @Transactional(propagation = Propagation.MANDATORY)
    public ExceptionCaseActionTarget selectExceptionForUpdate(Long exceptionCaseId) {
        ExceptionCaseActionTarget target = exceptionCaseRepository.findByIdForUpdate(exceptionCaseId);
        return target != null && (ExceptionType.CAP_WARNING.name().equals(target.exceptionType())
                || ExceptionType.CAP_VIOLATION.name().equals(target.exceptionType())) ? target : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int insertExceptionAction(CapExceptionResolveCommand command, ExceptionCaseActionTarget target) {
        exceptionActionRepository.saveAndFlush(ExceptionAction.builder()
                .exceptionCaseId(target.exceptionCaseId())
                .actionSeq(exceptionActionRepository.findNextActionSeq(target.exceptionCaseId()))
                .fromStatus(target.status())
                .toStatus(ExceptionStatus.RESOLVED)
                .actionType(command.actionType())
                .reason(command.reason())
                .evidenceRef(command.evidenceRef())
                .actionBy(command.actionBy())
                .build());
        return 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int updateExceptionResolved(ExceptionCaseActionTarget target) {
        if (!ExceptionStatus.isOpen(target.status().name())) return 0;
        // 기존 DB 시각과 assigned_to 값을 보존하고 D 소유의 상태 갱신 API를 재사용한다.
        OffsetDateTime resolvedAt = (OffsetDateTime) entityManager
                .createNativeQuery("SELECT clock_timestamp()", OffsetDateTime.class).getSingleResult();
        int affectedRows = exceptionCaseRepository.updateCaseAfterAction(
                target.exceptionCaseId(), ExceptionStatus.RESOLVED, target.assignedTo(), resolvedAt);
        entityManager.clear();
        return affectedRows;
    }

    public boolean existsUnresolvedViolation(Long paymentId) {
        return !entityManager.createQuery("""
                SELECT e.exceptionCaseId FROM ExceptionCase e
                 WHERE e.sourceEntityType = 'COMMISSION_TRANSACTION'
                   AND e.sourceEntityId = :paymentId
                   AND e.exceptionType = :exceptionType
                   AND e.status IN (:newStatus, :reviewStatus)
                """, Long.class)
                .setParameter("paymentId", String.valueOf(paymentId))
                .setParameter("exceptionType", ExceptionType.CAP_VIOLATION)
                .setParameter("newStatus", ExceptionStatus.NEW)
                .setParameter("reviewStatus", ExceptionStatus.IN_REVIEW)
                .setMaxResults(1)
                .getResultList().isEmpty();
    }
}
