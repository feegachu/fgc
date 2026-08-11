package com.susukkang.fgc.validation.batch.contract;

import org.springframework.stereotype.Component;

/**
 * ValidationRunCompletionGate의 기본(자리표시자) 구현
 *
 * 실제로 채워질 때 들어갈 "완료 조건" 3가지
 *   1) 원장 불균형 0건
 *   2) 치명 예외 미해결 없음
 *   3) 정책 누락·중복 없음
 */
@Component
public class NoOpValidationRunCompletionGate implements ValidationRunCompletionGate {

    @Override
    public void verifyCompletable(ValidationStepContext context) {
        // 아직 미구현으로 구조만 잡아둠
    }
}
