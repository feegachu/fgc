package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.mapper.ValidationTargetSelectionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationTargetSelectionServiceTest {

    @Mock
    private ValidationTargetSelectionMapper validationTargetSelectionMapper;

    private ValidationTargetSelectionService service;

    @BeforeEach
    void setUp() {
        service = new ValidationTargetSelectionService(validationTargetSelectionMapper);
    }

    @Test
    void selectsTargetsUsingLastDayOfValidationMonth() {
        when(validationTargetSelectionMapper.insertTargets(118L, LocalDate.of(2026, 8, 31)))
                .thenReturn(15);

        int selectedCount = service.selectTargets(118L, LocalDate.of(2026, 8, 1));

        assertThat(selectedCount).isEqualTo(15);
        verify(validationTargetSelectionMapper)
                .insertTargets(118L, LocalDate.of(2026, 8, 31));
    }

    @Test
    void calculatesLastDayForLeapYearFebruary() {
        service.selectTargets(118L, LocalDate.of(2028, 2, 1));

        verify(validationTargetSelectionMapper)
                .insertTargets(118L, LocalDate.of(2028, 2, 29));
    }

    @Test
    void rejectsMissingValidationRunId() {
        assertThatThrownBy(() -> service.selectTargets(null, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("월 통합검증 실행 ID가 없습니다.");

        verify(validationTargetSelectionMapper, never()).insertTargets(null, LocalDate.of(2026, 8, 31));
    }

    @Test
    void rejectsMissingValidationMonth() {
        assertThatThrownBy(() -> service.selectTargets(118L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("검증 대상 월이 없습니다.");

        verify(validationTargetSelectionMapper, never()).insertTargets(118L, null);
    }
}
