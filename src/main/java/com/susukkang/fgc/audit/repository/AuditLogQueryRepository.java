package com.susukkang.fgc.audit.repository;

import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import com.susukkang.fgc.audit.dto.AuditUserRow;
import com.susukkang.fgc.audit.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 설명 : 감사 조회 전용 인터페이스. JPQL DTO 프로젝션으로 검색하고 저장·삭제 메서드는 노출하지 않는다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Transactional(readOnly = true)
public interface AuditLogQueryRepository extends Repository<AuditLog, Long> {

    // 2026-09-26 hjKang - 직접 조립하던 검색 조건을 선언형 JPQL로 전환한다.
    // 기존 코드: EntityManager로 WHERE 절과 매개변수를 조립했다.
    // 문제: 인터페이스 Repository 방식과 달라 별도 조회 구현을 관리해야 했다.
    // 개선: 목록·건수 쿼리가 동일한 선택 조건을 공유하고 값은 매개변수로 바인딩한다.
    // 조건의 생략 여부는 Boolean으로 전달해 NULL 값의 JDBC 타입 추론에 의존하지 않는다.
    String SEARCH_CONDITIONS = """
            where (:#{#criteria.entityType() == null} = true
                   or al.entityType = :#{#criteria.entityType()})
              and (:#{#criteria.entityId() == null} = true
                   or al.entityId = :#{#criteria.entityId()})
              and (:#{#criteria.userId() == null} = true
                   or al.userId = :#{#criteria.userId()})
              and (:#{#criteria.action() == null} = true
                   or al.actionCode = :#{#criteria.action()})
              and (:#{#criteria.fromTimestamp() == null} = true
                   or al.occurredAt >= :#{#criteria.fromTimestamp()})
              and (:#{#criteria.toExclusiveTimestamp() == null} = true
                   or al.occurredAt < :#{#criteria.toExclusiveTimestamp()})
            """;

    /**
     * 설명 : 검색 조건에 맞는 감사행을 처리자와 함께 페이징 조회하며 처리자 없는 행도 유지한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Query("""
            select new com.susukkang.fgc.audit.dto.AuditLogRow(
                al.auditLogId, al.occurredAt, al.userId, u.loginId,
                al.actionCode, al.entityType, al.entityId,
                cast(al.beforeValue as String), cast(al.afterValue as String),
                al.reason, al.requestId, al.policyVersionId)
            from AuditLog al left join al.user u
            """ + SEARCH_CONDITIONS + " order by al.occurredAt desc, al.auditLogId desc")
    List<AuditLogRow> selectAuditLogs(@Param("criteria") AuditLogSearchCriteria criteria, Pageable pageable);

    /**
     * 설명 : 목록과 동일한 검색 조건을 적용한 전체 감사로그 건수를 반환한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Query("select count(al) from AuditLog al " + SEARCH_CONDITIONS)
    long countAuditLogs(@Param("criteria") AuditLogSearchCriteria criteria);

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
