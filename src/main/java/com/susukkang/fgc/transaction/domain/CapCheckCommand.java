package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.CapResultStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 지급 확정 전 한도 점검 결과 저장 명령
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Getter
@Builder
public class CapCheckCommand {

    @Setter
    private Long capCheckId;
    private final Long paymentId;
    private final Long contractId;
    private final String paymentStage;
    private final Long capRuleSetId;
    private final Long refundRateTableId;
    private final LocalDate asOfDate;
    private final BigDecimal basePremiumAmount;
    private final BigDecimal refund12mAmount;
    private final BigDecimal complianceDeductionAmount;
    private final BigDecimal limitAmount;
    private final BigDecimal includedAmount;
    private final BigDecimal remainingAmount;
    private final BigDecimal usagePct;
    private final CapResultStatus resultStatus;
    private final String calculationSnapshotJson;
    private final Long commissionItemId;
    private final String itemCode;
    private final String itemName;
    private final Long transactionAttributionId;
    private final String classificationSnapshot;
    private final BigDecimal candidateAmount;
    private final String decisionReason;
    private final String evidenceRef;
}
