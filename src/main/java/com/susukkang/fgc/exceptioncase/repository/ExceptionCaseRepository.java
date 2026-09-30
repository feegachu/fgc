package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionExceptionTarget;
import com.susukkang.fgc.exceptioncase.entity.ExceptionCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 설명 : 공통 예외 저장 및 조치 직전 잠금·현재 상태 갱신 Repository
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
public interface ExceptionCaseRepository extends JpaRepository<ExceptionCase, Long> {

    // 관리 중인 엔티티의 이전 상태를 재사용하지 않고 잠근 DB 행을 DTO로 읽는다.
    // 잠금은 후속 순번 조회·이력 저장·상태 갱신까지 유지돼야 하므로 호출자 트랜잭션이 필수다.
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT exception_case_id AS "exceptionCaseId", exception_type AS "exceptionType",
                   status, assigned_to AS "assignedTo",
                   source_entity_type AS "sourceEntityType", source_entity_id AS "sourceEntityId"
              FROM fgc.exception_case
             WHERE exception_case_id = :exceptionCaseId
             FOR UPDATE
            """, nativeQuery = true)
    LockedCaseView findLockedById(@Param("exceptionCaseId") Long exceptionCaseId);

    default ExceptionCaseActionTarget findByIdForUpdate(Long exceptionCaseId) {
        LockedCaseView row = findLockedById(exceptionCaseId);
        return row == null ? null : new ExceptionCaseActionTarget(
                row.getExceptionCaseId(), row.getExceptionType(),
                ExceptionStatus.valueOf(row.getStatus()), row.getAssignedTo());
    }

    default JournalCorrectionExceptionTarget findJournalCorrectionTargetForUpdate(Long exceptionCaseId) {
        LockedCaseView row = findLockedById(exceptionCaseId);
        return row == null ? null : new JournalCorrectionExceptionTarget(
                row.getExceptionCaseId(), row.getExceptionType(), ExceptionStatus.valueOf(row.getStatus()),
                row.getSourceEntityType(), row.getSourceEntityId());
    }

    // 이력을 먼저 DB에 반영하고 상태를 바꾼다. 변경 후에는 위 DTO 잠금 조회로 최신 상태를 읽는다.
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ExceptionCase e
               SET e.status = :status, e.assignedTo = :assignedTo, e.resolvedAt = :resolvedAt
             WHERE e.exceptionCaseId = :exceptionCaseId
            """)
    int updateCaseAfterAction(@Param("exceptionCaseId") Long exceptionCaseId,
                              @Param("status") ExceptionStatus status,
                              @Param("assignedTo") Long assignedTo,
                              @Param("resolvedAt") OffsetDateTime resolvedAt);

    interface LockedCaseView {
        Long getExceptionCaseId();

        String getExceptionType();

        String getStatus();

        Long getAssignedTo();

        String getSourceEntityType();

        String getSourceEntityId();
    }
}
