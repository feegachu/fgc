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
    private boolean missingJournalEvidence;
    /** #518 이 검증 실행에서 검토필요(REVIEW_REQUIRED)로 보류된 대상 계약이다. */
    private boolean targetReviewRequired;
}
