package com.susukkang.fgc.audit.repository;

import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditUserRow;
import com.susukkang.fgc.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 설명 : 감사 조회 전용 인터페이스. JPQL DTO 프로젝션으로 검색하고 저장·삭제 메서드는 노출하지 않는다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Transactional(readOnly = true)
public interface AuditLogQueryRepository extends Repository<AuditLog, Long>, AuditLogSearch {

    /**
     * 설명 : 감사로그 한 건을 목록과 같은 프로젝션(처리자 LEFT JOIN)으로 조회한다. AUDT-W01 상세·diff(#407).
     */
    @Query("""
            select new com.susukkang.fgc.audit.dto.AuditLogRow(
                al.auditLogId, al.occurredAt, al.userId, u.loginId,
                al.actionCode, al.entityType, al.entityId,
                cast(al.beforeValue as String), cast(al.afterValue as String),
                al.reason, al.requestId, al.policyVersionId)
            from AuditLog al left join al.user u
            where al.auditLogId = :auditLogId
            """)
    Optional<AuditLogRow> selectAuditLog(@Param("auditLogId") Long auditLogId);

    /**
     * 설명 : 감사로그에 기록된 작업 코드를 중복 없이 정렬해 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Query("select distinct al.actionCode from AuditLog al order by al.actionCode")
    List<String> selectDistinctActionCodes();

    /**
     * 설명 : 감사로그에 기록된 대상 종류를 중복 없이 정렬해 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Query("select distinct al.entityType from AuditLog al order by al.entityType")
    List<String> selectDistinctEntityTypes();

    /**
     * 설명 : 감사로그를 남긴 사용자 ID와 로그인 ID를 중복 없이 조회한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Query("""
            select distinct new com.susukkang.fgc.audit.dto.AuditUserRow(al.userId, u.loginId)
            from AuditLog al join al.user u order by u.loginId
            """)
    List<AuditUserRow> selectAuditUsers();
}
