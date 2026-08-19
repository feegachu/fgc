package com.susukkang.fgc.common.exception;

import org.springframework.http.HttpStatus;

public enum FgcErrorCode {

    CAP_001(
            "FGC-CAP-001",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.cap.exceeded"
    ),
    CAP_002(
            "FGC-CAP-002",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.cap.reviewRequired"
    ),
    CAP_003(
            "FGC-CAP-003",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.cap.unresolvedViolation"
    ),
    CAP_004(
            "FGC-CAP-004",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.cap.ruleMissing"
    ),

    TRAN_001(
            "FGC-TRAN-001",
            HttpStatus.CONFLICT,
            "error.transaction.duplicate"
    ),
    TRAN_002(
            "FGC-TRAN-002",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.transaction.noAttribution"
    ),
    TRAN_003(
            "FGC-TRAN-003",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.transaction.attributionMismatch"
    ),
    TRAN_004(
            "FGC-TRAN-004",
            HttpStatus.BAD_REQUEST,
            "error.transaction.evidenceRequired"
    ),
    TRAN_005(
            "FGC-TRAN-005",
            HttpStatus.CONFLICT,
            "error.transaction.draftOnly"
    ),
    TRAN_006(
            "FGC-TRAN-006",
            HttpStatus.CONFLICT,
            "error.transaction.cancelConfirmedOnly"
    ),
    TRAN_007(
            "FGC-TRAN-007",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.transaction.policyVersionMissing"
    ),
    TRAN_008(
            "FGC-TRAN-008",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.transaction.attributionBeforeContract"
    ),

    CONT_001(
            "FGC-CONT-001",
            HttpStatus.CONFLICT,
            "error.contract.duplicate"
    ),
    CONT_002(
            "FGC-CONT-002",
            HttpStatus.BAD_REQUEST,
            "error.contract.futureDate"
    ),

    SCHE_001(
            "FGC-SCHE-001",
            HttpStatus.CONFLICT,
            "error.schedule.irreversible"
    ),
    SCHE_002(
            "FGC-SCHE-002",
            HttpStatus.CONFLICT,
            "error.schedule.confirmedImmutable"
    ),
    SCHE_003(
            "FGC-SCHE-003",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.schedule.capExceeded"
    ),
    SCHE_004(
            "FGC-SCHE-004",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.schedule.capReviewRequired"
    ),

    VRUN_001(
            "FGC-VRUN-001",
            HttpStatus.CONFLICT,
            "error.validationRun.alreadyRunning"
    ),
    VRUN_002(
            "FGC-VRUN-002",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.validationRun.conditionsRemaining"
    ),
    VRUN_003(
            "FGC-VRUN-003",
            HttpStatus.CONFLICT,
            "error.validationRun.finalizedImmutable"
    ),
    VRUN_004(
            "FGC-VRUN-004",
            HttpStatus.CONFLICT,
            "error.validationRun.invalidTransition"
    ),
    VRUN_005(
            "FGC-VRUN-005",
            HttpStatus.CONFLICT,
            "error.validationRun.stateConflict"
    ),

    LEDG_001(
            "FGC-LEDG-001",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.ledger.imbalance"
    ),
    LEDG_002(
            "FGC-LEDG-002",
            HttpStatus.CONFLICT,
            "error.ledger.alreadyPosted"
    ),
    //확인
    LEDG_003(
            "FGC-LEDG-003",
            HttpStatus.CONFLICT,
            "error.ledger.reverseOnly"
    ),

    JOURNAL_001(
            "FGC-JOURNAL-001",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.journal.invalidAccount"
    ),

    RECO_001(
            "FGC-RECO-001",
            HttpStatus.CONFLICT,
            "error.reconciliation.duplicateGroup"
    ),
    RECO_002(
            "FGC-RECO-002",
            HttpStatus.CONFLICT,
            "error.reconciliation.duplicateRun"
    ),
    RECO_003(
            "FGC-RECO-003",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.reconciliation.noValidationRun"
    ),
    RECO_004(
            "FGC-RECO-004",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.reconciliation.notCompleted"
    ),

    EXCP_001(
            "FGC-EXCP-001",
            HttpStatus.BAD_REQUEST,
            "error.exception.reasonRequired"
    ),
    EXCP_003(
            "FGC-EXCP-003",
            HttpStatus.CONFLICT,
            "error.exception.invalidTransition"
    ),

    AUDT_001(
            "FGC-AUDT-001",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "error.common.internal"
    ),

    AUTH_001(
            "FGC-AUTH-001",
            HttpStatus.UNAUTHORIZED,
            "error.auth.invalidCredentials"
    ),
    AUTH_002(
            "FGC-AUTH-002",
            HttpStatus.UNAUTHORIZED,
            "error.auth.sessionExpired"
    ),
    AUTH_003(
            "FGC-AUTH-003",
            HttpStatus.FORBIDDEN,
            "error.auth.forbidden"
    ),

    COMMON_002(
            "FGC-COMMON-002",
            HttpStatus.BAD_REQUEST,
            "error.common.validation"
    ),
    COMMON_004(
            "FGC-COMMON-004",
            HttpStatus.NOT_FOUND,
            "error.common.notFound"
    ),
    COMMON_500(
            "FGC-COMMON-500",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "error.common.internal"
    );

    private final String code;
    private final HttpStatus status;
    private final String messageKey;

    FgcErrorCode(
            String code,
            HttpStatus status,
            String messageKey
    ) {
        this.code = code;
        this.status = status;
        this.messageKey = messageKey;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
