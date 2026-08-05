package com.susukkang.fgc.transaction.domain;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class CapCheckCommand {

    @Setter
    private Long capCheckId;
    private final Long paymentId;
    private final Long contractId;
    private final String paymentStage;
    private final Long capRuleSetId;
    private final LocalDate asOfDate;
    private final BigDecimal basePremiumAmount;
    private final BigDecimal limitAmount;
    private final BigDecimal includedAmount;
    private final BigDecimal remainingAmount;
    private final BigDecimal usagePct;
    private final String resultStatus;
    private final String calculationSnapshotJson;
    private final Long commissionItemId;
    private final Long transactionAttributionId;
    private final String classificationSnapshot;
    private final BigDecimal candidateAmount;
    private final String decisionReason;
    private final String evidenceRef;
}
