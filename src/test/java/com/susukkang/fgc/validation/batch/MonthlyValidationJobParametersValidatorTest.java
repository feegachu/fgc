package com.susukkang.fgc.validation.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthlyValidationJobParametersValidatorTest {

    private final MonthlyValidationJobParametersValidator validator = new MonthlyValidationJobParametersValidator();

    private JobParameters validParameters() {
        return new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 12L)
                .addString("requestId", "r1")
                .toJobParameters();
    }

    @Test
    void acceptsValidParameters() {
        assertThatCode(() -> validator.validate(validParameters())).doesNotThrowAnyException();
    }

    @Test
    void rejectsNullParameters() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(JobParametersInvalidException.class);
    }

    @Test
    void rejectsInvalidValidationMonth() {
        JobParameters params = new JobParametersBuilder()
                .addString("validationMonth", "bogus")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 12L)
                .addString("requestId", "r1")
                .toJobParameters();

        assertThatThrownBy(() -> validator.validate(params))
                .isInstanceOf(JobParametersInvalidException.class)
                .hasMessageContaining("validationMonth");
    }
}
