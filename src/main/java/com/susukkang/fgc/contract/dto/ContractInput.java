package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : ContractInput
 * 계약 생성 , 계약 수정의 공용 Interface , 함수 재사용이 목적
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
public interface ContractInput {
    Long getInsurerId();
    Long getProductOfferingId();
    LocalDate getContractDate();
    ContractStatus getContractStatus();
    Long getAgentId();
    Long getOrganizationId();
    PaymentCycleCode getPaymentCycleCode();
    BigDecimal getFirstPremiumAmount();
    BigDecimal getMonthlyEquivalentFirstPremium();
    Integer getPaymentTermMonths();
    BigDecimal getStandardSurrenderDeductionAmount();
}