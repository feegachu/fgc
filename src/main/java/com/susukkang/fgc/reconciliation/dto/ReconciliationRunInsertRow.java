package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 설명 : reconciliation_run 생성 SQL 입력 모델
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Getter
@Setter
public class ReconciliationRunInsertRow {

    private Long reconciliationRunId;
    private LocalDate settlementMonth;
    private PaymentStage paymentStage;
    private Long insurerId;
    private Long validationRunId;
    private Long createdBy;

    public static ReconciliationRunInsertRow from(CreateReconciliationRunCommand command) {
        ReconciliationRunInsertRow row = new ReconciliationRunInsertRow();
        row.setSettlementMonth(command.settlementMonth());
        row.setPaymentStage(command.paymentStage());
        row.setInsurerId(command.insurerId());
        row.setValidationRunId(command.validationRunId());
        row.setCreatedBy(command.createdBy());
        return row;
    }
}
