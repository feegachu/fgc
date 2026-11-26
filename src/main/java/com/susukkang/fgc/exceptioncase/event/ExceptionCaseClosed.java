package com.susukkang.fgc.exceptioncase.event;

/**
 * 설명 : 예외 한 건이 종결(해결, 오탐·반려)됐다는 사건(IF-EVT-08).
 * 종결을 담은 트랜잭션 안에서 발행한다. 받는 쪽은 커밋 뒤에 처리한다.
 * 마감 알림이 이 사건을 받아 확정 조건을 바로 다시 본다(FGC-FUN-089).
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
public record ExceptionCaseClosed(Long exceptionCaseId) {
}
