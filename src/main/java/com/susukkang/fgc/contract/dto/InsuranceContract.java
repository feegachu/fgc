package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import com.susukkang.fgc.contract.domain.PremiumConversionRuleCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 설명 : InsuranceContract
 *  ContractRequest를 가공후
 *  insuranceContract에 저장되는 Entity
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InsuranceContract {

    private Long contractId;

    private Long insurerId;
    private Long productOfferingId;
    private String contractNo;
    private LocalDate contractDate;

    private Long agentId;
    private Long organizationId;

    private BigDecimal premiumPerCycleAmount;
    private BigDecimal firstPremiumAmount;
    private BigDecimal monthlyEquivalentFirstPremium;

    private PremiumConversionRuleCode premiumConversionRuleCode;
    private PaymentCycleCode paymentCycleCode;
    private Integer paymentTermMonths;

    private BigDecimal standardSurrenderDeductionAmount;
    private ContractStatus currentStatus;
    private DataOrigin dataOrigin;

    private Long createdBy;
}