package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationRunBatchProgressServiceImplTest {

    @Mock
    private ValidationRunRepository validationRunRepository;

    private ValidationRunBatchProgressServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValidationRunBatchProgressServiceImpl(validationRunRepository);
    }

    @Test
    void startRunningDelegatesToRepository() {
        when(validationRunRepository.transitionToRunning(1L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING))
                .thenReturn(1);

        service.startRunning(1L);

        verify(validationRunRepository)
                .transitionToRunning(1L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING);
    }

    @Test
    void startRunningThrowsWhenNoRowAffected() {
        when(validationRunRepository.transitionToRunning(1L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING))
                .thenReturn(0);

        assertThatThrownBy(() -> service.startRunning(1L))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    void advanceStepDelegatesToRepositoryWithStepNumber() {
        when(validationRunRepository.updateCurrentStep(1L, 3, ValidationRunStatus.RUNNING)).thenReturn(1);

        service.advanceStep(1L, 3);

        verify(validationRunRepository).updateCurrentStep(1L, 3, ValidationRunStatus.RUNNING);
    }

    @Test
    void advanceStepThrowsWhenNoRowAffected() {
        when(validationRunRepository.updateCurrentStep(1L, 3, ValidationRunStatus.RUNNING)).thenReturn(0);

        assertThatThrownBy(() -> service.advanceStep(1L, 3))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    // step=1은 startRunning 전담 — advanceStep(1)은 Repository도 안 부르고 바로 거부
    void advanceStepRejectsStepOne() {
        assertThatThrownBy(() -> service.advanceStep(1L, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void advanceStepRejectsStepAboveEight() {
        assertThatThrownBy(() -> service.advanceStep(1L, 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeRunDelegatesToRepository() {
        when(validationRunRepository.transitionToCompleted(1L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED))
                .thenReturn(1);

        service.completeRun(1L);

        verify(validationRunRepository)
                .transitionToCompleted(1L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED);
    }

    @Test
    void completeRunThrowsWhenNoRowAffected() {
        when(validationRunRepository.transitionToCompleted(1L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.completeRun(1L))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    void markFailedDelegatesToRepositoryWithStepAndMessage() {
        when(validationRunRepository.transitionToFailed(
                1L, 5, "boom", ValidationRunStatus.RUNNING, ValidationRunStatus.FAILED)).thenReturn(1);

        service.markFailed(1L, 5, "boom");

        verify(validationRunRepository)
                .transitionToFailed(1L, 5, "boom", ValidationRunStatus.RUNNING, ValidationRunStatus.FAILED);
    }

    @Test
    void markFailedThrowsWhenNoRowAffected() {
        when(validationRunRepository.transitionToFailed(
                1L, 5, "boom", ValidationRunStatus.RUNNING, ValidationRunStatus.FAILED)).thenReturn(0);

        assertThatThrownBy(() -> service.markFailed(1L, 5, "boom"))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    void markFailedRejectsStepZero() {
        assertThatThrownBy(() -> service.markFailed(1L, 0, "boom"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markFailedRejectsStepAboveEight() {
        assertThatThrownBy(() -> service.markFailed(1L, 9, "boom"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
