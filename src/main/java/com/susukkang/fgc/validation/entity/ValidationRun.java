package com.susukkang.fgc.validation.entity;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 월 통합검증 실행 엔티티. #379가 제공하는 공유 엔티티 — 생성/조회/잠금/상태전이 API의
 * 기반이며 #371·#373·#377·#378·#380이 참조한다.
 *
 * status/current_step의 실제 변경은 조건부 UPDATE({@code ValidationRunRepository}의
 * {@code @Modifying} 메서드)로만 이루어진다. 엔티티 필드를 직접 바꿔 save() 하는 경로는 두지
 * 않는다 — MyBatis 시절의 "WHERE validation_run_id=? AND status=?" 낙관적 락 효과를
 * 그대로 유지하기 위함이다.
 */
@Entity
@SqlResultSetMapping(name = "FinalizeChecklistCountsMapping", classes = @ConstructorResult(
        targetClass = FinalizeChecklistCounts.class,
        columns = {
                @ColumnResult(name = "validationRunId", type = Long.class),
                @ColumnResult(name = "validationMonth", type = LocalDate.class),
                @ColumnResult(name = "incompleteRunCount", type = Integer.class),
                @ColumnResult(name = "journalImbalanceCount", type = Long.class),
                @ColumnResult(name = "unresolvedCriticalExceptionCount", type = Long.class),
                @ColumnResult(name = "unresolvedPolicyExceptionCount", type = Long.class),
                @ColumnResult(name = "attributionImbalanceCount", type = Long.class),
                @ColumnResult(name = "capDetailMismatchCount", type = Long.class)
        }
))
@Table(
        name = "validation_run",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_validation_run", columnNames = {"validation_month", "run_no"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "validation_run_id", nullable = false)
    private Long validationRunId;

    @Column(name = "validation_month", nullable = false)
    private LocalDate validationMonth;

    @Column(name = "run_no", nullable = false)
    private Integer runNo;

    @Column(name = "run_type", nullable = false, length = 20)
    private String runType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ValidationRunStatus status;

    // DB 컬럼이 smallint라 Integer 그대로 두면 스키마 검증(ddl-auto=validate)이
    // int2 vs integer 불일치로 깨진다.
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "current_step", nullable = false)
    private Integer currentStep;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", nullable = false, columnDefinition = "jsonb")
    private String policySnapshotJson;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "finalized_at")
    private OffsetDateTime finalizedAt;

    @Column(name = "triggered_by")
    private Long triggeredBy;

    @Column(name = "finalized_by")
    private Long finalizedBy;

    @Column(name = "failure_message", length = 2000)
    private String failureMessage;

    @Column(name = "finalize_idempotency_key", length = 160)
    private String finalizeIdempotencyKey;

    // 생성 시각은 DB 기본값(clock_timestamp())으로만 채워진다. @Generated가 없으면 save()
    // 직후의 createdAt은 null로 남는다 — INSERT 뒤 findById를 다시 불러도 1차 캐시가 이미
    // 관리 중인 같은 인스턴스를 그대로 돌려줘서 갱신되지 않는다(Hibernate 세션 identity map).
    // @Generated(INSERT)는 INSERT 직후 Hibernate가 이 컬럼만 자동으로 재조회해 채워준다.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private ValidationRun(LocalDate validationMonth, Integer runNo, String runType,
                           Long triggeredBy, String policySnapshotJson) {
        this.validationMonth = validationMonth;
        this.runNo = runNo;
        this.runType = runType;
        this.triggeredBy = triggeredBy;
        this.policySnapshotJson = policySnapshotJson != null ? policySnapshotJson : "{}";
        this.status = ValidationRunStatus.CREATED;
        this.currentStep = 0;
    }
}
