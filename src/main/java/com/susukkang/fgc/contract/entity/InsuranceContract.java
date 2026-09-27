package com.susukkang.fgc.contract.entity;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.code.PremiumConversionRuleCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : 보험계약 엔티티
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Entity
@Table(
        name = "insurance_contract",
        schema = "fgc",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_contract_no",
                        columnNames = {"insurer_id", "contract_no"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InsuranceContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "insurer_id", nullable = false)
    private Long insurerId;

    @Column(name = "product_offering_id", nullable = false)
    private Long productOfferingId;

    @Column(name = "contract_no", nullable = false, length = 80)
    private String contractNo;

    @Column(name = "contract_date", nullable = false)
    private LocalDate contractDate;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    // 주기 보험료
    @Column(
            name = "premium_per_cycle_amount",
            nullable = false,
            precision = 15,
            scale = 2
    )
    private BigDecimal premiumPerCycleAmount;

    // 초회 보험료
    @Column(
            name = "first_premium_amount",
            nullable = false,
            precision = 15,
            scale = 2
    )
    private BigDecimal firstPremiumAmount;

    // 월납환산 초회 보험료
    @Column(
            name = "monthly_equivalent_first_premium",
            nullable = false,
            precision = 15,
            scale = 2
    )
    private BigDecimal monthlyEquivalentFirstPremium;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "premium_conversion_rule_code",
            nullable = false,
            length = 40
    )
    private PremiumConversionRuleCode premiumConversionRuleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_cycle_code", nullable = false, length = 20)
    private PaymentCycleCode paymentCycleCode;

    @Column(name = "payment_term_months", nullable = false)
    private Integer paymentTermMonths;

    // 표준해약공제액
    @Column(
            name = "standard_surrender_deduction_amount",
            precision = 15,
            scale = 2
    )
    private BigDecimal standardSurrenderDeductionAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 20)
    private ContractStatus currentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_origin", nullable = false, length = 20)
    private DataOrigin dataOrigin;

    @Column(name = "created_by")
    private Long createdBy;

    // DB 기본값으로 생성
    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime createdAt;

    // DB 기본값 및 UPDATE 트리거로 관리
    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime updatedAt;

    /** 계약 수정 요청의 업무 필드를 반영한다. 식별자와 원본 데이터 출처는 유지한다. */
    public void updateDetails(
            Long insurerId,
            Long productOfferingId,
            String contractNo,
            LocalDate contractDate,
            Long agentId,
            Long organizationId,
            BigDecimal premiumPerCycleAmount,
            BigDecimal firstPremiumAmount,
            BigDecimal monthlyEquivalentFirstPremium,
            PremiumConversionRuleCode premiumConversionRuleCode,
            PaymentCycleCode paymentCycleCode,
            Integer paymentTermMonths,
            BigDecimal standardSurrenderDeductionAmount,
            ContractStatus currentStatus
    ) {
        this.insurerId = insurerId;
        this.productOfferingId = productOfferingId;
        this.contractNo = contractNo;
        this.contractDate = contractDate;
        this.agentId = agentId;
        this.organizationId = organizationId;
        this.premiumPerCycleAmount = premiumPerCycleAmount;
        this.firstPremiumAmount = firstPremiumAmount;
        this.monthlyEquivalentFirstPremium = monthlyEquivalentFirstPremium;
        this.premiumConversionRuleCode = premiumConversionRuleCode;
        this.paymentCycleCode = paymentCycleCode;
        this.paymentTermMonths = paymentTermMonths;
        this.standardSurrenderDeductionAmount = standardSurrenderDeductionAmount;
        this.currentStatus = currentStatus;
    }

    // 생성 시 입력할 업무 필드만 Builder에 노출한다. ID와 생성·수정 시각은 DB가 관리한다.
    @Builder
    private InsuranceContract(
            Long insurerId,
            Long productOfferingId,
            String contractNo,
            LocalDate contractDate,
            Long agentId,
            Long organizationId,
            BigDecimal premiumPerCycleAmount,
            BigDecimal firstPremiumAmount,
            BigDecimal monthlyEquivalentFirstPremium,
            PremiumConversionRuleCode premiumConversionRuleCode,
            PaymentCycleCode paymentCycleCode,
            Integer paymentTermMonths,
            BigDecimal standardSurrenderDeductionAmount,
            ContractStatus currentStatus,
            DataOrigin dataOrigin
    ) {
        this.insurerId = insurerId;
        this.productOfferingId = productOfferingId;
        this.contractNo = contractNo;
        this.contractDate = contractDate;
        this.agentId = agentId;
        this.organizationId = organizationId;
        this.premiumPerCycleAmount = premiumPerCycleAmount;
        this.firstPremiumAmount = firstPremiumAmount;
        this.monthlyEquivalentFirstPremium = monthlyEquivalentFirstPremium;
        this.premiumConversionRuleCode = premiumConversionRuleCode;
        this.paymentCycleCode = paymentCycleCode;
        this.paymentTermMonths = paymentTermMonths;
        this.standardSurrenderDeductionAmount = standardSurrenderDeductionAmount;
        this.currentStatus = currentStatus;
        this.dataOrigin = dataOrigin;
    }
}
