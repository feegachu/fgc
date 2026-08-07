package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ValidationRunTransitionServiceImpl 단위테스트.
 * ValidationRunMapper를 mock으로 대체해 "찾기 → 전이 판단 → 조건부 UPDATE 호출" 흐름만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunTransitionServiceImplTest {

    @Mock
    private ValidationRunMapper validationRunMapper;

    private ValidationRunTransitionServiceImpl service;

    @BeforeEach
    // 필드 초기화(= new ...(validationRunMapper))로 바로 생성하면 안됨
    void setUp() {
        service = new ValidationRunTransitionServiceImpl(validationRunMapper);
    }

    private ValidationRunRow rowWithStatus(String status) {
        ValidationRunRow row = new ValidationRunRow();
        row.setStatus(status);
        return row;
    }

    @Test
    // 실행이 없으면(findById가 null) COMMON_004, DB에는 아예 안 간다
    void throwsNotFoundWhenRunDoesNotExist() {
        when(validationRunMapper.findById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.RUNNING))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.COMMON_004);

        verify(validationRunMapper, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    // 허용되지 않은 전이(COMPLETED→RUNNING)는 VRUN_004, DB에는 아예 안 간다
    void throwsInvalidTransitionForDisallowedTransition() {
        when(validationRunMapper.findById(1L)).thenReturn(rowWithStatus("COMPLETED"));

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.RUNNING))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_004);

        verify(validationRunMapper, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    // 전이 규칙은 통과했지만(RUNNING→COMPLETED) 조건부 UPDATE가 0건이면 VRUN_005(경합)
    void throwsStateConflictWhenConditionalUpdateAffectsZeroRows() {
        when(validationRunMapper.findById(1L)).thenReturn(rowWithStatus("RUNNING"));
        when(validationRunMapper.updateStatusIfCurrent(1L, "RUNNING", "COMPLETED")).thenReturn(0);

        assertThatThrownBy(() -> service.transition(1L, ValidationRunStatus.COMPLETED))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                .isEqualTo(FgcErrorCode.VRUN_005);
    }

    @Test
    // 정상 케이스 — UPDATE가 1건 반영되면 최신 상태(targetStatus)로 바뀐 행을 돌려줘야 한다
    void returnsUpdatedRowOnSuccessfulTransition() {
        when(validationRunMapper.findById(1L)).thenReturn(rowWithStatus("CREATED"));
        when(validationRunMapper.updateStatusIfCurrent(1L, "CREATED", "RUNNING")).thenReturn(1);

        ValidationRunRow result = service.transition(1L, ValidationRunStatus.RUNNING);

        assertThat(result.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    // FINALIZED는 최종 상태라 어떤 target을 줘도 VRUN_004로 막혀야 한다
    void blocksAnyTransitionFromFinalized() {
        when(validationRunMapper.findById(1L)).thenReturn(rowWithStatus("FINALIZED"));

        for (ValidationRunStatus target : ValidationRunStatus.values()) {
            assertThatThrownBy(() -> service.transition(1L, target))
                    .isInstanceOf(FgcBusinessException.class)
                    .extracting(e -> ((FgcBusinessException) e).getErrorCode())
                    .isEqualTo(FgcErrorCode.VRUN_004);
        }
    }
}
