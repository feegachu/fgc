package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : ContractUpdateRequest
 *  계약 수정시 들어오는 Request Body
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ContractUpdateRequest implements ContractInput {

    @NotBlank
    @Size(max = 80)
    private String contractNo; // 계약 번호
    @NotNull
    private Long insurerId; // 보험회사
    @NotNull
    private Long productOfferingId; // 상품 판매 버전
    @NotNull
    private LocalDate contractDate; // 계약일
    @NotNull
    private ContractStatus contractStatus; // 계약 상태
    @NotNull
    private Long agentId; // 모집 설계사
    @NotNull
    private Long organizationId; // 설계사 소속 조직
    @NotNull
    private PaymentCycleCode paymentCycleCode; // 보험료 납입 주기
    @NotNull
    @PositiveOrZero
    private BigDecimal premiumPerCycleAmount; // 주기 보험료
    @NotNull
    @PositiveOrZero
    private BigDecimal firstPremiumAmount; // 초회 보험료
    @NotNull
    @PositiveOrZero
    private BigDecimal monthlyEquivalentFirstPremium; //월납 환산 보험료
    @NotNull
    @Positive
    private Integer paymentTermMonths; // 보험료 납입 기간(개월)
    @PositiveOrZero
    private BigDecimal standardSurrenderDeductionAmount; // 표준 해약 공제액

}
