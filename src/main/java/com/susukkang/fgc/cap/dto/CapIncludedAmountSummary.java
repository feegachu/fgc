package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 계약·설계사·지급단계별 초년도 한도 산입액 합계 DTO. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapIncludedAmountSummary {
    private Long contractId;
    private Long agentId;
    private PaymentStage paymentStage;
    private BigDecimal includedAmount;
}
