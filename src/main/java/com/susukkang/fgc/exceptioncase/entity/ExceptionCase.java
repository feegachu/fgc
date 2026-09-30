package com.susukkang.fgc.exceptioncase.entity;

import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.code.ExceptionType;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 공통 예외 업무건. 조치 이력과 검출 이력은 별도 테이블에 보관한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-28
 */
@Entity
@Table(name = "exception_case", schema = "fgc", uniqueConstraints = {
        @UniqueConstraint(name = "uq_exception_key", columnNames = "exception_key")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExceptionCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exception_case_id", nullable = false)
    private Long exceptionCaseId;

    @Column(name = "exception_key", nullable = false, length = 500)
    private String exceptionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 50)
    private ExceptionType exceptionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 15)
    private ExceptionSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExceptionStatus status = ExceptionStatus.NEW;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "policy_version_id")
    private Long policyVersionId;

    @Column(name = "source_entity_type", nullable = false, length = 60)
    private String sourceEntityType;

    @Column(name = "source_entity_id", nullable = false, length = 100)
    private String sourceEntityId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // DB의 set_updated_at 트리거가 상태 변경 시각을 관리한다.
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "validation_month")
    private LocalDate validationMonth;

    @Column(name = "reason_code", length = 80)
    private String reasonCode;

    @Column(name = "first_detected_run_id")
    private Long firstDetectedRunId;

    @Column(name = "last_detected_run_id")
    private Long lastDetectedRunId;

    // 신규 업무건은 DB 기본값을 사용하며, 재검출 요약은 record_exception_detection이 갱신한다.
    @Column(name = "first_detected_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime lastDetectedAt;

    @Column(name = "detection_count", nullable = false, insertable = false, updatable = false)
    private Integer detectionCount;

    @Column(name = "cap_check_id")
    private Long capCheckId;

    // 신규 예외의 상태는 NEW이며 식별자·생성 시각·검출 횟수는 외부에서 지정하지 않는다.
    @Builder
    private ExceptionCase(String exceptionKey, ExceptionType exceptionType, ExceptionSeverity severity,
                          Long validationRunId, Long contractId, Long agentId, Long policyVersionId,
                          String sourceEntityType, String sourceEntityId, String title, String description,
                          Long assignedTo, OffsetDateTime dueAt, LocalDate validationMonth,
                          String reasonCode, Long firstDetectedRunId, Long lastDetectedRunId, Long capCheckId) {
        this.exceptionKey = exceptionKey;
        this.exceptionType = exceptionType;
        this.severity = severity;
        this.validationRunId = validationRunId;
        this.contractId = contractId;
        this.agentId = agentId;
        this.policyVersionId = policyVersionId;
        this.sourceEntityType = sourceEntityType;
        this.sourceEntityId = sourceEntityId;
        this.title = title;
        this.description = description;
        this.assignedTo = assignedTo;
        this.dueAt = dueAt;
        this.validationMonth = validationMonth;
        this.reasonCode = reasonCode;
        this.firstDetectedRunId = firstDetectedRunId;
        this.lastDetectedRunId = lastDetectedRunId;
        this.capCheckId = capCheckId;
    }
}
