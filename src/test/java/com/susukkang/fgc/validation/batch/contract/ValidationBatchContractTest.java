package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.ValidationRunType;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
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
}
