package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ValidationRunTransitionServiceImpl 단위테스트.
 * ValidationRunRepository를 mock으로 대체해 "찾기 → 전이 판단 → 조건부 UPDATE 호출" 흐름만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunTransitionServiceImplTest {

    @Mock
    private ValidationRunRepository validationRunRepository;

    private ValidationRunTransitionServiceImpl service;

    @BeforeEach
    // 필드 초기화(= new ...(validationRunRepository))로 바로 생성하면 안됨
    void setUp() {
        service = new ValidationRunTransitionServiceImpl(validationRunRepository);
    }

    private ValidationRun runWithStatus(ValidationRunStatus status) {
        ValidationRun run = ValidationRun.builder()
                .validationMonth(LocalDate.of(2026, 8, 1))
                .runNo(1)
                .runType("MONTHLY")
                .build();
        ReflectionTestUtils.setField(run, "validationRunId", 1L);
        ReflectionTestUtils.setField(run, "status", status);
        return run;
    }

    @Test
    // 실행이 없으면(findById가 empty) COMMON_004, DB에는 아예 안 간다
    void throwsNotFoundWhenRunDoesNotExist() {
        when(validationRunRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.RUNNING))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_004);

        verify(validationRunRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    // 허용되지 않은 전이(COMPLETED→RUNNING)는 VRUN_004, DB에는 아예 안 간다
    void throwsInvalidTransitionForDisallowedTransition() {
        when(validationRunRepository.findById(1L)).thenReturn(Optional.of(runWithStatus(ValidationRunStatus.COMPLETED)));

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.RUNNING))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_004);

        verify(validationRunRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    // UPDATE가 0건이어도 실행이 여전히 존재하면(상태만 바뀐 경합) VRUN_005
    void throwsStateConflictWhenConditionalUpdateAffectsZeroRows() {
        when(validationRunRepository.findById(1L))
                .thenReturn(Optional.of(runWithStatus(ValidationRunStatus.RUNNING)))
                .thenReturn(Optional.of(runWithStatus(ValidationRunStatus.FAILED)));
        when(validationRunRepository.updateStatusIfCurrent(1L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.COMPLETED))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    // UPDATE가 0건이고 재조회에서도 사라졌으면(그 사이 삭제) 경합이 아니라 COMMON_004
    void throwsNotFoundWhenRunDeletedBetweenFindAndUpdate() {
        when(validationRunRepository.findById(1L))
                .thenReturn(Optional.of(runWithStatus(ValidationRunStatus.RUNNING)))
                .thenReturn(Optional.empty());
        when(validationRunRepository.updateStatusIfCurrent(1L, ValidationRunStatus.RUNNING, ValidationRunStatus.COMPLETED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.COMPLETED))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_004);
    }

    @Test
    // 정상 케이스 — UPDATE 성공 뒤 재조회한 행(트리거가 채운 컬럼 포함)을 돌려줘야 한다
    void returnsUpdatedRowOnSuccessfulTransition() {
        when(validationRunRepository.findById(1L))
                .thenReturn(Optional.of(runWithStatus(ValidationRunStatus.CREATED)))
                .thenReturn(Optional.of(runWithStatus(ValidationRunStatus.RUNNING)));
        when(validationRunRepository.updateStatusIfCurrent(1L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING))
                .thenReturn(1);

        ValidationRunRow result = service.transition(1L, ValidationRunStatus.RUNNING);

        assertThat(result.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    // UPDATE는 성공(affected==1)했지만 재조회 사이에 행이 삭제됐으면 null을 그대로 반환하지 않고
    // COMMON_004로 던져야 한다 — updateStatusIfCurrent가 성공을 보고했다고 해서 그 뒤의 재조회까지
    // 항상 값이 있다고 가정하면 안 된다.
    void throwsNotFoundWhenRunDeletedAfterSuccessfulUpdate() {
        when(validationRunRepository.findById(1L))
                .thenReturn(Optional.of(runWithStatus(ValidationRunStatus.CREATED)))
                .thenReturn(Optional.empty());
        when(validationRunRepository.updateStatusIfCurrent(1L, ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING))
                .thenReturn(1);

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.RUNNING))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_004);
    }

    @Test
    // FINALIZED는 최종 상태라 어떤 target을 줘도 VRUN_004로 막혀야 한다
    void blocksAnyTransitionFromFinalized() {
        when(validationRunRepository.findById(1L)).thenReturn(Optional.of(runWithStatus(ValidationRunStatus.FINALIZED)));

        for (ValidationRunStatus target : ValidationRunStatus.values()) {
            assertThatThrownBy(() -> service.transition(1L, target))
                    .isInstanceOf(FgcBusinessException.class)
                    .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                    .isEqualTo(FgcErrorCode.VRUN_004);
        }
    }
}
