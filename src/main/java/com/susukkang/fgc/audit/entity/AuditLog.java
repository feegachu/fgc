package com.susukkang.fgc.audit.entity;

import com.susukkang.fgc.auth.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * 설명 : 추가 기록 전용 감사 엔티티. 기존 ID를 받지 않는 생성 메서드와 불변 매핑으로 추가 기록 계약을 유지한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
@Entity
@Table(name = "audit_log")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id")
    private Long auditLogId;

    @Generated(event = EventType.INSERT)
    @Column(name = "occurred_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "user_id")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private AppUser user;

    @Column(name = "action_code", nullable = false, length = 50)
    private String actionCode;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 100)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value", columnDefinition = "jsonb")
    private String beforeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value", columnDefinition = "jsonb")
    private String afterValue;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "request_id", length = 80)
    private String requestId;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "client_ip", columnDefinition = "inet")
    private String clientIp;

    @Column(name = "policy_version_id")
    private Long policyVersionId;

    /**
     * 설명 : 입력 값으로 ID가 없는 신규 감사 엔티티를 생성하며 기존 감사행을 지정하는 생성 경로는 제공하지 않는다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    public static AuditLog create(Long userId, String actionCode, String entityType, String entityId,
                                  String beforeValue, String afterValue, String reason, String requestId,
                                  String clientIp, Long policyVersionId) {
        return new AuditLog(userId, actionCode, entityType, entityId, beforeValue, afterValue,
                reason, requestId, clientIp, policyVersionId);
    }

    /**
     * 설명 : 사용자와 작업 대상 및 변경 전후 값을 담은 신규 감사로그 엔티티를 생성한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    private AuditLog(Long userId, String actionCode, String entityType, String entityId,
                     String beforeValue, String afterValue, String reason, String requestId,
                     String clientIp, Long policyVersionId) {
        this.userId = userId;
        this.actionCode = actionCode;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
        this.reason = reason;
        this.requestId = requestId;
        this.clientIp = clientIp;
        this.policyVersionId = policyVersionId;
    }
}
