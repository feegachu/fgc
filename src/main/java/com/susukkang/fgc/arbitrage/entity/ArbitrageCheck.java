package com.susukkang.fgc.arbitrage.entity;

import com.susukkang.fgc.common.code.*;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 차익거래 계산 결과와 재현용 스냅샷. 스키마·채번·생성 시각은 기존 DB가 관리한다. */
@Entity
@Table(name = "arbitrage_check", schema = "fgc", uniqueConstraints = @UniqueConstraint(
        name = "uq_arbitrage_check", columnNames = {"validation_run_id", "contract_id", "payment_stage", "as_of_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ArbitrageCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "arbitrage_check_id", nullable = false)
    private Long arbitrageCheckId;

    @Column(name = "validation_run_id", nullable = false)
    private Long validationRunId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", nullable = false, length = 20)
    private PaymentStage paymentStage;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "contract_month_no", nullable = false)
    private Integer contractMonthNo;

    @Column(name = "cumulative_paid_premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal cumulativePaidPremium;

    @Column(name = "paid_commission_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal paidCommissionAmount;

    @Column(name = "planned_commission_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal plannedCommissionAmount;

    @Column(name = "included_surrender_value_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal includedSurrenderValueAmount;

    @Column(name = "refund_addition_applied_yn", nullable = false)
    private Boolean refundAdditionAppliedYn;

    @Enumerated(EnumType.STRING)
    @Column(name = "surrender_value_source_type", nullable = false, length = 20)
    private SurrenderValueSourceType surrenderValueSourceType;

    @Column(name = "net_difference_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal netDifferenceAmount;

    @Column(name = "refund_rate_table_id", nullable = true)
    private Long refundRateTableId;

    @Column(name = "standard_deduction_80_yn", nullable = false)
    private Boolean standardDeduction80Yn;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 20)
    private ArbitrageCheckStatus resultStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_snapshot", nullable = false, columnDefinition = "jsonb")
    private String calculationSnapshot;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
