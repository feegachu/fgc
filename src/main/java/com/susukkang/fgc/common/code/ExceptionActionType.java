package com.susukkang.fgc.common.code;

/**
 * 설명 : 한도 예외 해결 시 사용하는 해결조치 유형
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ExceptionActionType {
    ASSIGN,           // 담당 배정
    START_REVIEW,     // 검토 시작
    CORRECT,          // 정정
    REDUCE,           // 감액
    CANCEL,           // 취소
    DEFER,            // 이연
    RECONCILE_AGAIN,  // 재대사
    FALSE_POSITIVE,   // 오탐
    RESOLVE,          // 해결
    REJECT,           // 반려
    COMMENT;          // 의견

    /** 현재 예외 상태에서 이 조치를 수행할 수 있는지 검사한다. 종결 상태는 재처리하지 않는다. */
    public boolean supports(ExceptionStatus currentStatus) {
        return switch (this) {
            case ASSIGN, COMMENT ->
                    currentStatus == ExceptionStatus.NEW
                            || currentStatus == ExceptionStatus.IN_REVIEW;
            case START_REVIEW -> currentStatus == ExceptionStatus.NEW;
            case CORRECT, REDUCE, CANCEL, DEFER, RECONCILE_AGAIN,
                    FALSE_POSITIVE, RESOLVE, REJECT ->
                    currentStatus == ExceptionStatus.IN_REVIEW;
        };
    }

    /** 조치 완료 후 exception_case에 저장할 현재 상태를 반환한다. */
    public ExceptionStatus nextStatus(ExceptionStatus currentStatus) {
        if (!supports(currentStatus)) {
            throw new IllegalStateException("현재 상태에서는 해당 예외 조치를 수행할 수 없습니다.");
        }
        return switch (this) {
            case ASSIGN, COMMENT -> currentStatus;
            case START_REVIEW -> ExceptionStatus.IN_REVIEW;
            case CORRECT, REDUCE, CANCEL, DEFER, RECONCILE_AGAIN, RESOLVE ->
                    ExceptionStatus.RESOLVED;
            case FALSE_POSITIVE, REJECT -> ExceptionStatus.REJECTED;
        };
    }
}
