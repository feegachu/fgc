package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
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
    private ValidationRunMapper validationRunMapper;

    private ValidationRunBatchProgressServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValidationRunBatchProgressServiceImpl(validationRunMapper);
    }

    @Test
    void startRunningDelegatesToMapper() {
        when(validationRunMapper.transitionToRunning(1L)).thenReturn(1);

        service.startRunning(1L);

        verify(validationRunMapper).transitionToRunning(1L);
    }

    @Test
    void startRunningThrowsWhenNoRowAffected() {
        when(validationRunMapper.transitionToRunning(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.startRunning(1L))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    void advanceStepDelegatesToMapperWithStepNumber() {
        when(validationRunMapper.updateCurrentStep(1L, 3)).thenReturn(1);

        service.advanceStep(1L, 3);

        verify(validationRunMapper).updateCurrentStep(1L, 3);
    }

    @Test
    void advanceStepThrowsWhenNoRowAffected() {
        when(validationRunMapper.updateCurrentStep(1L, 3)).thenReturn(0);

        assertThatThrownBy(() -> service.advanceStep(1L, 3))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    void completeRunDelegatesToMapper() {
        when(validationRunMapper.transitionToCompleted(1L)).thenReturn(1);

        service.completeRun(1L);

        verify(validationRunMapper).transitionToCompleted(1L);
    }

    @Test
    void completeRunThrowsWhenNoRowAffected() {
        when(validationRunMapper.transitionToCompleted(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.completeRun(1L))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    void markFailedDelegatesToMapperWithStepAndMessage() {
        when(validationRunMapper.transitionToFailed(1L, 5, "boom")).thenReturn(1);

        service.markFailed(1L, 5, "boom");

        verify(validationRunMapper).transitionToFailed(1L, 5, "boom");
    }

    @Test
    void markFailedThrowsWhenNoRowAffected() {
        when(validationRunMapper.transitionToFailed(1L, 5, "boom")).thenReturn(0);

        assertThatThrownBy(() -> service.markFailed(1L, 5, "boom"))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }
}
