package com.susukkang.fgc.validation.entity;

import com.susukkang.fgc.validation.dto.ValidationScheduleState;
import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 설명 : 월 통합검증 대상 선정 결과 엔티티. 행 자체는 {@code ValidationTargetRepository}의
 * CTE 기반 네이티브 벌크 INSERT(insertTargets)로만 채워진다 — 대상 선별 규칙(환급률표 매칭,
 * 상품코드 비교 등)은 DB 전용 집합 연산이라 JPQL/엔티티 조작으로 옮기지 않고 네이티브 SQL을
 * 유지한다(#379 이슈의 "DB 전용 기능은 네이티브 SQL" 방침).
 */
@Entity
@SqlResultSetMapping(name = "ValidationScheduleStateMapping", classes = @ConstructorResult(
        targetClass = ValidationScheduleState.class,
        columns = {
                @ColumnResult(name = "contractId", type = Long.class),
                @ColumnResult(name = "paymentStage", type = String.class),
                @ColumnResult(name = "scheduleHeaderId", type = Long.class),
                @ColumnResult(name = "policyVersionId", type = Long.class),
                @ColumnResult(name = "scheduleVersion", type = Integer.class),
                @ColumnResult(name = "scheduleStatus", type = String.class),
                @ColumnResult(name = "activeHeaderCount", type = Long.class),
                @ColumnResult(name = "lineCount", type = Long.class),
                @ColumnResult(name = "distinctLineCount", type = Long.class),
                @ColumnResult(name = "totalAmount", type = BigDecimal.class)
        }
))
@Table(
        name = "validation_target",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_validation_target", columnNames = {"validation_run_id", "contract_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "validation_target_id", nullable = false)
    private Long validationTargetId;

    @Column(name = "validation_run_id", nullable = false)
    private Long validationRunId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "product_offering_id", nullable = false)
    private Long productOfferingId;

    @Column(name = "refund_rate_table_id")
    private Long refundRateTableId;

    // DB CHECK 제약: SELECTED | EXCLUDED | REVIEW_REQUIRED. 다른 영역도 문자열로만
    // 참조하고 있어(ValidationTargetItemResponse) 별도 enum을 새로 만들지 않는다.
    @Column(name = "selection_status", nullable = false, length = 20)
    private String selectionStatus;

    @Column(name = "selection_reason", length = 1000)
    private String selectionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private String snapshotJson;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
