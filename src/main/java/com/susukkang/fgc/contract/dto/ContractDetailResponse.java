package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : ResponseContractDetail
 * 계약 상세보기 결과 응답 DTO
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Setter
@Builder
@RequiredArgsConstructor
@AllArgsConstructor
public class ContractDetailResponse {
    private Long insurerId;
    private Long productOfferingId;
    private Long agentId;
    private Long organizationId;
    //기본 정보 탭
    private String contractNo; //계약번호
    private String productName; //상품명
    private LocalDate contractDate; //계약일
    private String consumerName; //계약자
    private BigDecimal premiumPerCycleAmount; //주기 보험료
    private PaymentCycleCode paymentCycleCode; //납입주기
    private BigDecimal firstPremiumAmount; //초회 보험료
    private BigDecimal monthlyEquivalentFirstPremium; //월납 환산 보험료
    private Integer paymentTermMonths; //납입기간
    private BigDecimal standardSurrenderDeductionAmount; //표준해약공제액
    private ContractStatus contractStatus; //계약상태
    private DataOrigin dataOrigin; //계약 출처

    //계약상태 사건 이력 List 2차
    //private List<ContractStatusEventResponse> contractStatusEventResponses;
}
