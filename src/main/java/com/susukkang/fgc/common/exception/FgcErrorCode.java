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

    TRAN_009(
            "FGC-TRAN-009",
            HttpStatus.CONFLICT,
            "error.transaction.deleteLinked"
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
    CONT_003(
            "FGC-CONT-003",
            HttpStatus.CONFLICT,
            "error.contract.concurrentUpdate"
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
    VRUN_006(
            "FGC-VRUN-006",
            HttpStatus.CONFLICT,
            "error.validationRun.idempotencyKeyConflict"
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
            HttpStatus.METHOD_NOT_ALLOWED,
            "error.ledger.reverseOnly"
    ),
    LEDG_004(
            "FGC-LEDG-004",
            HttpStatus.CONFLICT,
            "error.ledger.correctionConflict"
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
    EXCP_004(
            "FGC-EXCP-004",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "error.exception.invalidJournalCorrectionSource"
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
    AUTH_005(
            "FGC-AUTH-005",
            HttpStatus.FORBIDDEN,
            "error.auth.agentScope"
    ),
    AUTH_004(
            "FGC-AUTH-004",
            HttpStatus.UNAUTHORIZED,
            "error.auth.superseded"
    ),

    // IF-EXT-03 외부 LLM 장애·건너뜀 — AI 기능만 끄고 기본 업무는 계속한다(SER-013).
    AI_001(
            "FGC-AI-001",
            HttpStatus.SERVICE_UNAVAILABLE,
            "error.ai.unavailable"
    ),

    POL_001("FGC-POL-001", HttpStatus.CONFLICT, "error.policy.selfApproval"),
    POL_002("FGC-POL-002", HttpStatus.UNPROCESSABLE_ENTITY, "error.policy.overlap"),
    POL_003("FGC-POL-003", HttpStatus.CONFLICT, "error.policy.stateChanged"),
    IMPL_IDEMPOTENCY_CONFLICT("FGC-IMPL-IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT,
            "error.common.idempotencyConflict"),
    IMPL_REQUEST_IN_PROGRESS("FGC-IMPL-REQUEST_IN_PROGRESS", HttpStatus.CONFLICT,
            "error.common.requestInProgress"),
    // FUN-025·026 계약 상태 사건(인터페이스정의서 §14-1 예약 코드)
    IMPL_AGENT_FIXED("FGC-IMPL-AGENT_FIXED", HttpStatus.UNPROCESSABLE_ENTITY, "error.contract.agentFixed"),
    IMPL_EVENT_ORDER("FGC-IMPL-EVENT_ORDER", HttpStatus.UNPROCESSABLE_ENTITY, "error.contract.eventOrder"),
    IMPL_TRANSITION_DENIED("FGC-IMPL-TRANSITION_DENIED", HttpStatus.CONFLICT, "error.contract.transitionDenied"),

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
