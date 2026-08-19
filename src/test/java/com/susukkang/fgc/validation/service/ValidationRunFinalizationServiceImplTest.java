package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.FinalizedValidationRunRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FGC-FUN-044-01/02 체크리스트 판정과 확정 상태 전이 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class ValidationRunFinalizationServiceImplTest {

    @Mock
    private ValidationRunMapper validationRunMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ValidationRunFinalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValidationRunFinalizationServiceImpl(
                validationRunMapper, auditLogService, eventPublisher);
        // Mockito의 Long 기본값 0L과 MyBatis 단건 SELECT 무결과 null의 차이를 제거한다.
        lenient().when(validationRunMapper.findValidationRunIdByFinalizeIdempotencyKey(any()))
                .thenReturn(null);
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
                "/exceptions?validationRunId=44&severity=CRITICAL&status=OPEN",
                "/exceptions?validationRunId=44&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN",
                "/transactions?settlementMonth=2026-08&attributionImbalanceOnly=true",
                "/validation-runs/44");
    }

    @Test
    void finalizesCompletedRunAndWritesAuditInSameServiceCall() {
        when(validationRunMapper.findByIdForUpdate(44L)).thenReturn(run("COMPLETED", 8));
        when(validationRunMapper.findFinalizeChecklistCounts(44L)).thenReturn(passingCounts());
        when(validationRunMapper.finalizeIfCompleted(44L, 7L, "finalize-44")).thenReturn(1);
        when(validationRunMapper.findFinalizationById(44L)).thenReturn(finalized("finalize-44"));
        FinalizeValidationRunResponse response = service.finalizeRun(44L, 7L, " finalize-44 ");

        assertThat(response.status()).isEqualTo("FINALIZED");
        assertThat(response.finalizedBy()).isEqualTo("gaadmin");
        verify(validationRunMapper).finalizeIfCompleted(44L, 7L, "finalize-44");
        verify(auditLogService).record(any());
        verify(eventPublisher).publishEvent(new ValidationRunFinalized(
                44L, LocalDate.of(2026, 8, 1), 7L,
                OffsetDateTime.parse("2026-08-16T12:34:56+09:00")));
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalFinalizationWithoutAnotherAudit() {
        when(validationRunMapper.findValidationRunIdByFinalizeIdempotencyKey("finalize-44"))
                .thenReturn(44L);
        when(validationRunMapper.findByIdForUpdate(44L)).thenReturn(run("FINALIZED", 10));
        when(validationRunMapper.findFinalizationById(44L)).thenReturn(finalized("finalize-44"));

        FinalizeValidationRunResponse response = service.finalizeRun(44L, 7L, "finalize-44");

        assertThat(response.status()).isEqualTo("FINALIZED");
        verify(validationRunMapper, never()).finalizeIfCompleted(any(), any(), any());
        verify(auditLogService, never()).record(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void differentKeyCannotMutateFinalizedRun() {
        when(validationRunMapper.findByIdForUpdate(44L)).thenReturn(run("FINALIZED", 10));
        when(validationRunMapper.findFinalizationById(44L)).thenReturn(finalized("first-key"));

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "different-key"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_003));
    }

    @Test
    void rejectsFinalizationWhenAnyChecklistConditionRemains() {
        FinalizeChecklistCounts counts = passingCounts();
        counts.setUnresolvedCriticalExceptionCount(3);
        counts.setCapDetailMismatchCount(1);
        when(validationRunMapper.findByIdForUpdate(44L)).thenReturn(run("COMPLETED", 8));
        when(validationRunMapper.findFinalizeChecklistCounts(44L)).thenReturn(counts);

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "finalize-44"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_002);
                    assertThat(exception.getParams()).containsEntry("n", 2L);
                });

        verify(validationRunMapper, never()).finalizeIfCompleted(any(), any(), any());
    }

    @Test
    void rejectsInvalidStateBeforeCheckingResults() {
        when(validationRunMapper.findByIdForUpdate(44L)).thenReturn(run("RUNNING", 6));

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "finalize-44"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_004));

        verify(validationRunMapper, never()).findFinalizeChecklistCounts(44L);
    }

    @Test
    void rejectsIdempotencyKeyOwnedByAnotherRun() {
        when(validationRunMapper.findValidationRunIdByFinalizeIdempotencyKey("shared-key"))
                .thenReturn(43L);

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "shared-key"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_005));

        verify(validationRunMapper, never()).findByIdForUpdate(44L);
    }

    @Test
    void rejectsBlankIdempotencyKeyWhenHeaderIsProvided() {
        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "  "))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
    }

    @Test
    void finalizesWithoutOptionalIdempotencyKey() {
        when(validationRunMapper.findByIdForUpdate(44L)).thenReturn(run("COMPLETED", 8));
        when(validationRunMapper.findFinalizeChecklistCounts(44L)).thenReturn(passingCounts());
        when(validationRunMapper.finalizeIfCompleted(44L, 7L, null)).thenReturn(1);
        when(validationRunMapper.findFinalizationById(44L)).thenReturn(finalized(null));
        assertThat(service.finalizeRun(44L, 7L, null).status()).isEqualTo("FINALIZED");
        verify(validationRunMapper, never()).findValidationRunIdByFinalizeIdempotencyKey(any());
    }

    @Test
    void requiresFinalizingUserIdEvenThoughLegacyColumnIsNullable() {
        assertThatThrownBy(() -> service.finalizeRun(44L, null, "finalize-44"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
        verify(validationRunMapper, never()).findByIdForUpdate(any());
    }

    private FinalizeChecklistCounts passingCounts() {
        FinalizeChecklistCounts counts = new FinalizeChecklistCounts();
        counts.setValidationRunId(44L);
        counts.setValidationMonth(LocalDate.of(2026, 8, 1));
        return counts;
    }

    private ValidationRunRow run(String status, int currentStep) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(44L);
        row.setValidationMonth(LocalDate.of(2026, 8, 1));
        row.setStatus(status);
        row.setCurrentStep(currentStep);
        return row;
    }

    private FinalizedValidationRunRow finalized(String idempotencyKey) {
        FinalizedValidationRunRow row = new FinalizedValidationRunRow();
        row.setValidationRunId(44L);
        row.setStatus("FINALIZED");
        row.setFinalizedAt(OffsetDateTime.parse("2026-08-16T12:34:56+09:00"));
        row.setFinalizedBy("gaadmin");
        row.setFinalizeIdempotencyKey(idempotencyKey);
        return row;
    }
}
