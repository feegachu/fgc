package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import com.susukkang.fgc.validation.repository.ValidationTargetRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ValidationRunProgressTraceTest {
    @Mock private ValidationRunRepository runs;
    @Mock private ValidationTargetRepository targets;
    @InjectMocks private ValidationRunDetailServiceImpl service;

    private ValidationRun run() {
        return ValidationRun.builder().validationMonth(LocalDate.of(2026, 7, 1))
                .runNo(1).runType("MONTHLY").triggeredBy(1L).build();
    }

    @Test
    void progressIncludesLatestAttemptWithoutChangingStepDerivedPercentage() {
        given(runs.findById(1L)).willReturn(Optional.of(run()));
        var trace = mock(ValidationRunRepository.AttemptTrace.class);
        given(trace.getAttemptId()).willReturn(42L);
        given(trace.getRequestId()).willReturn("retry-request");
        given(runs.findLatestAttemptTrace(1L)).willReturn(Optional.of(trace));
        var response = service.progress(1L);
        assertThat(response.attemptId()).isEqualTo(42L);
        assertThat(response.requestId()).isEqualTo("retry-request");
        assertThat(response.status()).isEqualTo("CREATED");
        assertThat(response.currentStep()).isZero();
        assertThat(response.progressPct()).isZero();
    }

    @Test
    void legacyRunWithoutStartAuditKeepsProgressAvailable() {
        given(runs.findById(1L)).willReturn(Optional.of(run()));
        given(runs.findLatestAttemptTrace(1L)).willReturn(Optional.empty());
        assertThat(service.progress(1L).attemptId()).isNull();
    }

    @Test
    void missingRunDoesNotLookUpAuditTrace() {
        given(runs.findById(1L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.progress(1L)).isInstanceOf(FgcBusinessException.class);
        verify(runs, never()).findLatestAttemptTrace(1L);
    }
}
