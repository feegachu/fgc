package com.susukkang.fgc.audit.repository;

import com.susukkang.fgc.audit.entity.AuditLog;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 공용 감사 저장 인터페이스. 저장 후 동기화만 제공하고 삭제·일반 CRUD 메서드는 노출하지 않는다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /**
     * 설명 : 서비스가 생성한 신규 감사 엔티티를 저장하고 DB 오류를 호출 중에 확인하도록 즉시 동기화한다.
     * 기존 트랜잭션에 참여하고 없으면 새로 시작한다(REQUIRED).
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    @Transactional
    <S extends AuditLog> S saveAndFlush(S auditLog);
}
