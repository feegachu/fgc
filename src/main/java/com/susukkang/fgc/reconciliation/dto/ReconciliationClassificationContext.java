package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

/** DB에만 존재하는 계약·조직·정책·분개 분류 신호. */
@Getter
@Setter
public class ReconciliationClassificationContext {
    private boolean invalidContractPayment;
    private boolean organizationMismatch;
    private boolean policyVersionError;
    private boolean journalImbalance;
}
