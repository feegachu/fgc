package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
/**
 * 설명 : 계약데이터 생성시 받는 요청 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContractCreateRequest implements ContractInput {
    //계약 기본
    @NotNull
    private Long insurerId; //보험회사
    @NotBlank
    private String contractNo; //계약번호
    @NotNull
    private Long productOfferingId; //상품판매버전
    @NotNull
    private LocalDate contractDate; //계약일
    //회의
    @NotNull
    private ContractStatus contractStatus; //계약상태
    //모집 설계사 소속
    @NotNull
    private Long agentId; //설계사
    @NotNull
    private Long organizationId;
    //보험료 납입조건
    @NotNull
    private PaymentCycleCode paymentCycleCode; //납입주기
    @NotNull
    @Positive
    private BigDecimal premiumPerCycleAmount; //주기 보험료
    @NotNull
    @Positive
    private BigDecimal firstPremiumAmount; //초회 보험료
    @NotNull
    @Positive
    private BigDecimal monthlyEquivalentFirstPremium; //월납 환산 보험료
    @NotNull
    @Positive
    private Integer paymentTermMonths; //납입기간
    @NotNull
    @Positive
    private BigDecimal standardSurrenderDeductionAmount; //계약별 표준해약공제액 입력값
}
