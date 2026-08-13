package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** GET /api/v1/contracts/{id}/journals 응답의 분개 라인 1건. */
@Getter
@Builder
public class ContractJournalLineResponse {
    private final Integer lineNo;
    private final String accountCode;
    private final String accountName;
    private final BigDecimal debitAmount;
    private final BigDecimal creditAmount;
    private final Long agentId;
    private final String paymentStage;
    private final String paymentStageLabel;
    private final Long commissionItemId;
    private final String memo;

    public static ContractJournalLineResponse from(ContractJournalLineRow row) {
        PaymentStage paymentStage = row.getPaymentStage() == null
                ? null : PaymentStage.valueOf(row.getPaymentStage());
        return ContractJournalLineResponse.builder()
                .lineNo(row.getLineNo())
                .accountCode(row.getAccountCode())
                .accountName(row.getAccountName())
                .debitAmount(row.getDebitAmount())
                .creditAmount(row.getCreditAmount())
                .agentId(row.getAgentId())
                .paymentStage(row.getPaymentStage())
                .paymentStageLabel(paymentStage == null ? null : paymentStage.label())
                .commissionItemId(row.getCommissionItemId())
                .memo(row.getMemo())
                .build();
    }
}
