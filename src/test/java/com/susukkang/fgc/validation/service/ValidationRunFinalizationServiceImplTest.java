package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** FGC-FUN-044-01 체크리스트 응답의 고정 순서·문구·판정을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ValidationRunFinalizationServiceImplTest {

    @Mock
    private ValidationRunMapper validationRunMapper;

    private ValidationRunFinalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValidationRunFinalizationServiceImpl(validationRunMapper);
    }

    @Test
    void returnsSixConditionsInCanonicalOrderAndLabels() {
        FinalizeChecklistCounts counts = passingCounts();
        counts.setJournalImbalanceCount(2);
        when(validationRunMapper.findFinalizeChecklistCounts(44L)).thenReturn(counts);

        FinalizeChecklistResponse response = service.getChecklist(44L);

        assertThat(response.passed()).isFalse();
        assertThat(response.conditions()).extracting("no").containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(response.conditions()).extracting("label").containsExactly(
                "검증 실행 상태가 계산완료(COMPLETED)인가",
                "원장 불균형(차변≠대변)이 0건인가",
                "심각도 긴급(CRITICAL) 미처리 예외가 0건인가",
                "정책 없음 · 정책 중복이 0건인가",
                "귀속합계 오류가 0건인가",
                "계약별 상세 합계 = 실행 요약 합계인가");
        assertThat(response.conditions().get(1).passed()).isFalse();
        assertThat(response.conditions().get(1).count()).isEqualTo(2);
        assertThat(response.conditions()).extracting("linkUrl").containsExactly(
                "/validation-runs/44",
                "/api/v1/journals/imbalances?validationRunId=44",
                "/api/v1/exceptions?validationRunId=44&severity=CRITICAL&status=OPEN",
                "/api/v1/exceptions?validationRunId=44&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN",
                "/transactions?settlementMonth=2026-08&attributionImbalanceOnly=true",
                "/validation-runs/44?section=cap-details");
    }

    private FinalizeChecklistCounts passingCounts() {
        FinalizeChecklistCounts counts = new FinalizeChecklistCounts();
        counts.setValidationRunId(44L);
        counts.setValidationMonth(LocalDate.of(2026, 8, 1));
        return counts;
    }
}
