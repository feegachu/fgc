package com.susukkang.fgc.cap.entity;

import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 계약별·지급단계별 초년도 1,200% 한도 판정 및 계산 스냅샷 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-30
 */
@Entity
@Table(
        name = "cap_check",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_cap_check_monthly",
                        columnNames = {"validation_run_id", "contract_id", "payment_stage"}
                )
        }
)

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CapCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cap_check_id", nullable = false)
    private Long capCheckId;

    @Column(name = "validation_run_id")
    private Long validationRunId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", nullable = false, length = 20)
    private PaymentStage paymentStage;

    @Column(name = "cap_rule_set_id", nullable = false)
    private Long capRuleSetId;

    @Column(name = "refund_rate_table_id")
    private Long refundRateTableId;

    // 지급 확정 직전 판정의 대상이 된 수수료 지급 건
    @Column(name = "candidate_transaction_id")
    private Long candidateTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_kind", nullable = false, length = 20)
    private CapCheckKind checkKind;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "base_premium_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal basePremiumAmount;

    @Column(name = "refund_12m_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal refund12mAmount = BigDecimal.ZERO;

    @Column(name = "compliance_deduction_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal complianceDeductionAmount = BigDecimal.ZERO;

    @Column(name = "limit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal limitAmount;

    @Column(name = "included_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal includedAmount;

    // 한도를 초과하면 음수가 될 수 있다.
    @Column(name = "remaining_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal remainingAmount;

    @Column(name = "usage_pct", precision = 12, scale = 6)
    private BigDecimal usagePct;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 20)
    private CapResultStatus resultStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_snapshot", nullable = false, columnDefinition = "jsonb")
    private String calculationSnapshotJson = "{}";

    // 판정·생성 시각은 DB 기본값으로 생성한다.
    @Column(name = "checked_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime checkedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private CapCheck(Long validationRunId, Long contractId, PaymentStage paymentStage,
                     Long capRuleSetId, Long refundRateTableId, Long candidateTransactionId,
                     CapCheckKind checkKind, LocalDate asOfDate, BigDecimal basePremiumAmount,
                     BigDecimal refund12mAmount, BigDecimal complianceDeductionAmount,
                     BigDecimal limitAmount, BigDecimal includedAmount, BigDecimal remainingAmount,
                     BigDecimal usagePct, CapResultStatus resultStatus, String calculationSnapshotJson) {
        this.validationRunId = validationRunId;
        this.contractId = contractId;
        this.paymentStage = paymentStage;
        this.capRuleSetId = capRuleSetId;
        this.refundRateTableId = refundRateTableId;
        this.candidateTransactionId = candidateTransactionId;
        this.checkKind = checkKind;
        this.asOfDate = asOfDate;
        this.basePremiumAmount = basePremiumAmount;
        this.refund12mAmount = refund12mAmount != null ? refund12mAmount : BigDecimal.ZERO;
        this.complianceDeductionAmount = complianceDeductionAmount != null
                ? complianceDeductionAmount : BigDecimal.ZERO;
        this.limitAmount = limitAmount;
        this.includedAmount = includedAmount;
        this.remainingAmount = remainingAmount;
        this.usagePct = usagePct;
        this.resultStatus = resultStatus;
        this.calculationSnapshotJson = calculationSnapshotJson != null ? calculationSnapshotJson : "{}";
    }
}
