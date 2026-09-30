package com.susukkang.fgc.exceptioncase.entity;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;

/**
 * 설명 : 추가 기록 전용 예외 조치 이력. 기존 행의 수정·삭제는 DB 트리거도 차단한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
@Entity
@Table(name = "exception_action", schema = "fgc", uniqueConstraints = {
        @UniqueConstraint(name = "uq_exception_action_seq", columnNames = {"exception_case_id", "action_seq"})
})
@Immutable
@DynamicInsert
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExceptionAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exception_action_id", nullable = false, updatable = false)
    private Long exceptionActionId;

    @Column(name = "exception_case_id", nullable = false, updatable = false)
    private Long exceptionCaseId;

    @Column(name = "action_seq", nullable = false, updatable = false)
    private Integer actionSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private ExceptionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private ExceptionStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40, updatable = false)
    private ExceptionActionType actionType;

    @Column(name = "reason", nullable = false, length = 2000, updatable = false)
    private String reason;

    @Column(name = "evidence_ref", length = 500, updatable = false)
    private String evidenceRef;

    @Column(name = "action_by", nullable = false, updatable = false)
    private Long actionBy;

    // 조치 서비스가 지정한 시각을 보존한다. 최초 요청 이력에서 생략하면 DB 기본값을 사용한다.
    @Column(name = "action_at", updatable = false)
    private OffsetDateTime actionAt;

    @Builder
    private ExceptionAction(Long exceptionCaseId, Integer actionSeq, ExceptionStatus fromStatus,
                            ExceptionStatus toStatus, ExceptionActionType actionType, String reason,
                            String evidenceRef, Long actionBy, OffsetDateTime actionAt) {
        this.exceptionCaseId = exceptionCaseId;
        this.actionSeq = actionSeq;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actionType = actionType;
        this.reason = reason;
        this.evidenceRef = evidenceRef;
        this.actionBy = actionBy;
        this.actionAt = actionAt;
    }
}
