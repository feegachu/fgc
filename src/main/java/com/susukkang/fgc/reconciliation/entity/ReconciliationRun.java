package com.susukkang.fgc.reconciliation.entity;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 대사 실행 매핑. 상태 전이는 DB 가드를 사용하는 조건부 UPDATE로 처리한다.
 * 외부 영역 참조는 소유 엔티티를 중복 정의하지 않고 기존 식별자로 보존한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Entity
@Table(name = "reconciliation_run", schema = "fgc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reconciliation_run_id")
    private Long reconciliationRunId;
    @Column(name = "validation_run_id")
    private Long validationRunId;
    @Column(name = "settlement_month", nullable = false)
    private LocalDate settlementMonth;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", nullable = false, length = 20)
    private PaymentStage paymentStage;
    @Column(name = "insurer_id")
    private Long insurerId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ValidationRunStatus status = ValidationRunStatus.CREATED;
    @Column(name = "tolerance_policy_version_id")
    private Long tolerancePolicyVersionId;
    @Column(name = "started_at")
    private OffsetDateTime startedAt;
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
    @Column(name = "finalized_at")
    private OffsetDateTime finalizedAt;
    @Column(name = "finalized_by")
    private Long finalizedBy;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static ReconciliationRun create(ReconciliationRunInsertRow row) {
        ReconciliationRun run = new ReconciliationRun();
        run.validationRunId = row.getValidationRunId();
        run.settlementMonth = row.getSettlementMonth();
        run.paymentStage = row.getPaymentStage();
        run.insurerId = row.getInsurerId();
        run.createdBy = row.getCreatedBy();
        return run;
    }
}
