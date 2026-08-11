package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.ValidationRunType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;

/** 원장·예외 도메인이 아직 없어 항상 통과해야 한다는 계약만 확인한다(NoOpValidationRunCompletionGate 클래스 주석 참고). */
class NoOpValidationRunCompletionGateTest {

    @Test
    void alwaysAllowsCompletion() {
        NoOpValidationRunCompletionGate gate = new NoOpValidationRunCompletionGate();
        ValidationStepContext context = new ValidationStepContext(100L,
                new ValidationJobContext(LocalDate.of(2026, 8, 1), 1L, ValidationRunType.MONTHLY, 12L, "request-1"));

        assertThatCode(() -> gate.verifyCompletable(context)).doesNotThrowAnyException();
    }
}
