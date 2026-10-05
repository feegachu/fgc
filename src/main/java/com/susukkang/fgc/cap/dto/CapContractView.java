package com.susukkang.fgc.cap.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * cap 계산에 필요한 계약·상품 정보. CapContractQueryRepository의 JPQL 생성자 프로젝션 결과.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CapContractView {
    private Long contractId;
    private LocalDate contractDate;
    private BigDecimal monthlyEquivalentFirstPremium;
    private Integer paymentTermMonths;
    private BigDecimal standardSurrenderDeductionAmount;
    private Long insurerId;
    private Long productOfferingId;
    private String channelCode;
    private Boolean standardDeduction80Yn;
    private Long productId;
    private String productGroupCode;
}
