package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationBatchContractTest {
    @Test void rejectsInvalidContractSkip() {
        assertThatThrownBy(() -> new ContractSkip(null, "", null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsImbalanceBeyondInspectedCount() {
        assertThatThrownBy(() -> new LedgerImbalanceResult(1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNonFirstDayValidationMonth() {
        assertThatThrownBy(() -> new ValidationJobContext(LocalDate.of(2026, 8, 10), 1L,
                ValidationRunType.MONTHLY, 12L, "request-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    // #75: ValidationRunCompletionGate가 쓰는 ValidationStepContext를 만들 때 이 변환을 쓴다 —
    // MonthlyValidationJobParameters(JobParameters 파싱 결과)의 필드 5개가 그대로 옮겨져야 한다.
    void fromCopiesAllFieldsFromJobParameters() {
        MonthlyValidationJobParameters params = new MonthlyValidationJobParameters(
                LocalDate.of(2026, 8, 1), 7L, ValidationRunType.MONTHLY, 12L, "request-1");

        ValidationJobContext context = ValidationJobContext.from(params);

        assertThat(context.validationMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(context.runNo()).isEqualTo(7L);
        assertThat(context.runType()).isEqualTo(ValidationRunType.MONTHLY);
        assertThat(context.triggeredBy()).isEqualTo(12L);
        assertThat(context.requestId()).isEqualTo("request-1");
    }
}
