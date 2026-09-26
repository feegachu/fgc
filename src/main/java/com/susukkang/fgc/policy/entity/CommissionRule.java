package com.susukkang.fgc.policy.entity;

import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
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
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 설명 : 정책 버전의 적용 범위 및 회차별 수수료 계산 규칙.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "commission_rule")
@SqlResultSetMapping(
        name = "ResolvedCommissionRuleMapping",
        classes = @ConstructorResult(
                targetClass = ResolvedCommissionRule.class,
                columns = {
                        @ColumnResult(name = "fee_component_type", type = String.class),
                        @ColumnResult(name = "commission_rule_id", type = Long.class),
                        @ColumnResult(name = "commission_item_id", type = Long.class),
                        @ColumnResult(name = "agent_rank_code", type = String.class),
                        @ColumnResult(name = "installment_from", type = Integer.class),
                        @ColumnResult(name = "installment_to", type = Integer.class),
                        @ColumnResult(name = "basis_code", type = String.class),
                        @ColumnResult(name = "calculation_type", type = String.class),
                        @ColumnResult(name = "rate_pct", type = BigDecimal.class),
                        @ColumnResult(name = "fixed_amount", type = BigDecimal.class),
                        @ColumnResult(name = "rounding_scale", type = Integer.class),
                        @ColumnResult(name = "rounding_mode", type = String.class),
                        @ColumnResult(name = "payment_condition_code", type = String.class),
                        @ColumnResult(name = "insurer_id", type = Long.class),
                        @ColumnResult(name = "organization_id", type = Long.class),
                        @ColumnResult(name = "product_offering_id", type = Long.class),
                        @ColumnResult(name = "priority_no", type = Integer.class)
                }
        )
)
@Getter
public class CommissionRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "commission_rule_id", nullable = false)
    private Long commissionRuleId;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_stage", nullable = false, length = 20)
    private PaymentStage paymentStage;

    @Column(name = "insurer_id")
    private Long insurerId;

    @Column(name = "product_offering_id")
    private Long productOfferingId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "agent_rank_code", length = 30)
    private AgentRankCode agentRankCode;

    @Column(name = "commission_item_id", nullable = false)
    private Long commissionItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_component_type", nullable = false, length = 40)
    private FeeComponentType feeComponentType;

    @Column(name = "installment_from", nullable = false)
    private Integer installmentFrom;

    @Column(name = "installment_to", nullable = false)
    private Integer installmentTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_type", nullable = false, length = 15)
    private CalculationType calculationType;

    @Column(name = "basis_code", nullable = false, length = 40)
    private String basisCode;

    @Column(name = "rate_pct", precision = 9, scale = 6)
    private BigDecimal ratePct;

    @Column(name = "fixed_amount", precision = 15, scale = 2)
    private BigDecimal fixedAmount;

    @Column(name = "rounding_scale", nullable = false)
    private Short roundingScale = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false, length = 20)
    private RoundingMode roundingMode = RoundingMode.HALF_UP;

    @Column(name = "equal_monthly_yn", nullable = false)
    private boolean equalMonthlyYn;

    @Column(name = "payment_condition_code", length = 50)
    private String paymentConditionCode;

    @Column(name = "priority_no", nullable = false)
    private Integer priorityNo = 100;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_expression", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> ruleExpression = new HashMap<>();

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
