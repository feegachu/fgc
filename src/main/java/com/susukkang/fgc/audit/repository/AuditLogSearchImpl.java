package com.susukkang.fgc.audit.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.susukkang.fgc.audit.dto.AuditLogRow;
import com.susukkang.fgc.audit.dto.AuditLogSearchCriteria;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static com.susukkang.fgc.audit.entity.QAuditLog.auditLog;
import static com.susukkang.fgc.auth.entity.QAppUser.appUser;

@Transactional(readOnly = true)
public class AuditLogSearchImpl implements AuditLogSearch {
    private final JPAQueryFactory queryFactory;

    // Repository fragment는 DataJpaTest에서도 자동 구성되는 공유 EntityManager만 요구한다.
    public AuditLogSearchImpl(EntityManager entityManager) {
        this.queryFactory = new JPAQueryFactory(entityManager);
    }

    @Override
    public List<AuditLogRow> selectAuditLogs(AuditLogSearchCriteria criteria, Pageable pageable) {
        var al = auditLog;
        return queryFactory.select(Projections.constructor(AuditLogRow.class,
                        al.auditLogId, al.occurredAt, al.userId, appUser.loginId,
                        al.actionCode, al.entityType, al.entityId,
                        Expressions.stringTemplate("cast({0} as string)", al.beforeValue),
                        Expressions.stringTemplate("cast({0} as string)", al.afterValue),
                        al.reason, al.requestId, al.policyVersionId))
                .from(al).leftJoin(al.user, appUser).where(filters(criteria))
                .orderBy(al.occurredAt.desc(), al.auditLogId.desc())
                .offset(pageable.getOffset()).limit(pageable.getPageSize()).fetch();
    }

    @Override
    public long countAuditLogs(AuditLogSearchCriteria criteria) {
        return queryFactory.select(auditLog.count()).from(auditLog).where(filters(criteria)).fetchOne();
    }

    private BooleanBuilder filters(AuditLogSearchCriteria c) {
        var al = auditLog;
        BooleanBuilder where = new BooleanBuilder();
        if (c.entityType() != null) where.and(al.entityType.eq(c.entityType()));
        if (c.entityId() != null) where.and(al.entityId.eq(c.entityId()));
        if (c.userId() != null) where.and(al.userId.eq(c.userId()));
        if (c.action() != null) where.and(al.actionCode.eq(c.action()));
        if (c.fromTimestamp() != null) where.and(al.occurredAt.goe(c.fromTimestamp()));
        if (c.toExclusiveTimestamp() != null) where.and(al.occurredAt.lt(c.toExclusiveTimestamp()));
        return where;
    }
}
