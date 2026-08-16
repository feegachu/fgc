package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.journal.domain.NormalBalance;

import java.math.BigDecimal;

/** 검증원장 상세 응답의 분개 라인 1건. debitAmount/creditAmount 중 한쪽만 0보다 크다. */
public record JournalDetailLineResponse(
        Integer lineNo,
        String accountCode,
        String accountName,
        NormalBalance normalBalance,
        String normalBalanceLabel,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        Long contractId,
        Long agentId,
        String agentName,
        String paymentStage,
        String paymentStageLabel,
        Long commissionItemId,
        String commissionItemName,
        String memo
) {
    public static JournalDetailLineResponse from(JournalDetailLineRow row) {
        NormalBalance normalBalance = NormalBalance.valueOf(row.getNormalBalance());
        PaymentStage paymentStage = row.getPaymentStage() == null ? null : PaymentStage.valueOf(row.getPaymentStage());
        return new JournalDetailLineResponse(
                row.getLineNo(), row.getAccountCode(), row.getAccountName(),
                normalBalance, normalBalance.label(),
                row.getDebitAmount(), row.getCreditAmount(),
                row.getContractId(), row.getAgentId(), row.getAgentName(),
                row.getPaymentStage(), paymentStage == null ? null : paymentStage.label(),
                row.getCommissionItemId(), row.getCommissionItemName(), row.getMemo());
    }
}
