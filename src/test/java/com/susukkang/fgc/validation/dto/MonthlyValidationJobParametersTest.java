package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunType;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthlyValidationJobParametersTest {

    private JobParametersBuilder validBuilder() {
        return new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 12L)
                .addString("requestId", "20260803-7f3a1c");
    }

    @Test
    void parsesAllFieldsFromValidJobParameters() {
        MonthlyValidationJobParameters params = MonthlyValidationJobParameters.from(validBuilder().toJobParameters());

        assertThat(params.validationMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(params.runNo()).isEqualTo(1L);
        assertThat(params.runType()).isEqualTo(ValidationRunType.MONTHLY);
        assertThat(params.triggeredBy()).isEqualTo(12L);
        assertThat(params.requestId()).isEqualTo("20260803-7f3a1c");
    }

    @Test
    void rejectsInvalidValidationMonthFormat() {
        JobParameters params = validBuilder().addString("validationMonth", "2026/08").toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validationMonth");
    }

    @Test
    void rejectsMissingRunNo() {
        JobParameters params = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 12L)
                .addString("requestId", "r1")
                .toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runNo");
    }

    @Test
    void rejectsRunNoBelowOne() {
        JobParameters params = validBuilder().addLong("runNo", 0L).toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runNo");
    }

    @Test
    void rejectsRunNoOutsideValidationRunIntegerRange() {
        JobParameters params = validBuilder().addLong("runNo", (long) Integer.MAX_VALUE + 1).toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runNo");
    }

    @Test
    void rejectsUnknownRunType() {
        JobParameters params = validBuilder().addString("runType", "BOGUS").toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runType");
    }

    @Test
    void rejectsMissingTriggeredBy() {
        JobParameters params = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addString("requestId", "r1")
                .toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggeredBy");
    }

    @Test
    void rejectsBlankRequestId() {
        JobParameters params = validBuilder().addString("requestId", "  ").toJobParameters();

        assertThatThrownBy(() -> MonthlyValidationJobParameters.from(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestId");
    }
}
