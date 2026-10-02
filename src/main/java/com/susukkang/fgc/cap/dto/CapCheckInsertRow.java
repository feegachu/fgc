package com.susukkang.fgc.cap.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * cap_check 업무키 UPSERT 입력. 저장소가 생성하거나 재사용한 capCheckId를 채운다.
 * CapCheckWriteRepository와 소비자 전환이 남은 공유 Mapper가 같은 DTO를 사용한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapCheckInsertRow {
    private Long capCheckId;
    private Long validationRunId;
    private Long contractId;
    private String paymentStage;
    private Long capRuleSetId;
    private Long refundRateTableId;
    private String checkKind;
    private LocalDate asOfDate;
    private BigDecimal basePremiumAmount;
    private BigDecimal refund12mAmount;
    private BigDecimal complianceDeductionAmount;
    private BigDecimal limitAmount;
    private BigDecimal includedAmount;
    private BigDecimal remainingAmount;
    private BigDecimal usagePct;
    private String resultStatus;
    private String calculationSnapshotJson;
}
