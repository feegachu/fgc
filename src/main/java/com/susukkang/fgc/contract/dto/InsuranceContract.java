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
import java.time.OffsetDateTime;

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

    // #76 DailyChangedContractJob이 "이 계약이 정말 바뀌었나"를 contract_status_event 유무뿐
    // 아니라 insurance_contract 자체의 변경 시각으로도 판단해야 해서 추가했다(코드리뷰 지적,
    // 2026-08-11 — ContractService#updateContract가 보험료·납입기간 등 스케줄에 영향을 주는
    // 필드를 바꿔도 contract_status_event는 남기지 않기 때문에, 상태 이벤트 유무만으로는
    // "스케줄 재생성이 필요한가"를 완전히 판단할 수 없다).
    private OffsetDateTime updatedAt;
}