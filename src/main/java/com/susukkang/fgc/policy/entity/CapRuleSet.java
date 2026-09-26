package com.susukkang.fgc.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 지급단계·계약일·적용범위별 초년도 1,200% 한도 룰셋.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
@Entity
@Table(name = "cap_rule_set")
@Getter
public class CapRuleSet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cap_rule_set_id", nullable = false)
    private Long capRuleSetId;

    @Column(name = "policy_version_id", nullable = false)
    private Long policyVersionId;

    @Column(name = "payment_stage", nullable = false, length = 20)
    private String paymentStage;

    @Column(name = "contract_date_from", nullable = false)
    private LocalDate contractDateFrom;

    @Column(name = "contract_date_to")
    private LocalDate contractDateTo;

    @Column(name = "insurer_id")
    private Long insurerId;

    @Column(name = "product_group_code", length = 40)
    private String productGroupCode;

    @Column(name = "channel_code", length = 30)
    private String channelCode;

    @Column(name = "first_year_months", nullable = false)
    private Integer firstYearMonths = 12;

    @Column(name = "premium_multiplier", nullable = false, precision = 7, scale = 4)
    private BigDecimal premiumMultiplier = new BigDecimal("12.0000");

    @Column(name = "compliance_deduction_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal complianceDeductionPct = BigDecimal.ZERO;

    @Column(name = "refund_addition_condition", nullable = false, length = 40)
    private String refundAdditionCondition = "NONE";

    @Column(name = "warning_usage_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal warningUsagePct = new BigDecimal("90.0000");

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
