package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.FinalizedValidationRunRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import com.susukkang.fgc.validation.event.ValidationRunFinalized;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

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
    private ValidationRunRepository validationRunRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ValidationRunFinalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ValidationRunFinalizationServiceImpl(
                validationRunRepository, auditLogService, eventPublisher);
        lenient().when(validationRunRepository.findValidationRunIdByFinalizeIdempotencyKey(any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void returnsSixConditionsInCanonicalOrderAndLabels() {
        FinalizeChecklistCounts counts = passingCounts();
        counts.setJournalImbalanceCount(2);
        when(validationRunRepository.findFinalizeChecklistCounts(44L)).thenReturn(Optional.of(counts));

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
                // #330: 조건 3·4는 집계와 같은 월 기준으로 이동해야 건수와 목록이 일치한다
                "/exceptions?validationMonth=2026-08-01&severity=CRITICAL&status=OPEN",
                "/exceptions?validationMonth=2026-08-01&types=POLICY_MISSING&types=POLICY_DUPLICATE&status=OPEN",
                "/transactions?settlementMonth=2026-08&attributionImbalanceOnly=true",
                "/validation-runs/44");
    }

    @Test
    void finalizesCompletedRunAndWritesAuditInSameServiceCall() {
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.COMPLETED, 8)));
        when(validationRunRepository.findFinalizeChecklistCounts(44L)).thenReturn(Optional.of(passingCounts()));
        when(validationRunRepository.finalizeIfCompleted(
                44L, 7L, "finalize-44", ValidationRunStatus.COMPLETED, ValidationRunStatus.FINALIZED))
                .thenReturn(1);
        when(validationRunRepository.findFinalizationById(44L)).thenReturn(Optional.of(finalized("finalize-44")));

        FinalizeValidationRunResponse response = service.finalizeRun(44L, 7L, " finalize-44 ");

        assertThat(response.status()).isEqualTo("FINALIZED");
        assertThat(response.finalizedBy()).isEqualTo("gaadmin");
        verify(validationRunRepository).finalizeIfCompleted(
                44L, 7L, "finalize-44", ValidationRunStatus.COMPLETED, ValidationRunStatus.FINALIZED);
        verify(auditLogService).record(any());
        verify(eventPublisher).publishEvent(new ValidationRunFinalized(
                44L, LocalDate.of(2026, 8, 1), 7L,
                OffsetDateTime.parse("2026-08-16T12:34:56+09:00")));
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalFinalizationWithoutAnotherAudit() {
        when(validationRunRepository.findValidationRunIdByFinalizeIdempotencyKey("finalize-44"))
                .thenReturn(Optional.of(44L));
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.FINALIZED, 10)));
        when(validationRunRepository.findFinalizationById(44L)).thenReturn(Optional.of(finalized("finalize-44")));

        FinalizeValidationRunResponse response = service.finalizeRun(44L, 7L, "finalize-44");

        assertThat(response.status()).isEqualTo("FINALIZED");
        verify(validationRunRepository, never()).finalizeIfCompleted(any(), any(), any(), any(), any());
        verify(auditLogService, never()).record(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void concurrentSameKeyRequestRecoversOriginalResultWhenConditionalUpdateLosesRace() {
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.COMPLETED, 8)));
        when(validationRunRepository.findFinalizeChecklistCounts(44L)).thenReturn(Optional.of(passingCounts()));
        // UPDATE 0건은 잠금 획득 전 다른 요청이 동일 실행을 먼저 확정한 경합 결과를 모델링한다.
        when(validationRunRepository.finalizeIfCompleted(
                44L, 7L, "finalize-44", ValidationRunStatus.COMPLETED, ValidationRunStatus.FINALIZED))
                .thenReturn(0);
        when(validationRunRepository.findFinalizationById(44L)).thenReturn(Optional.of(finalized("finalize-44")));

        FinalizeValidationRunResponse response = service.finalizeRun(44L, 7L, "finalize-44");

        assertThat(response.status()).isEqualTo("FINALIZED");
        assertThat(response.finalizedAt()).isEqualTo(
                OffsetDateTime.parse("2026-08-16T12:34:56+09:00"));
        verify(auditLogService, never()).record(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void differentKeyCannotMutateFinalizedRun() {
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.FINALIZED, 10)));
        when(validationRunRepository.findFinalizationById(44L)).thenReturn(Optional.of(finalized("first-key")));

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "different-key"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_003));
    }

    @Test
    void rejectsFinalizationWhenAnyChecklistConditionRemains() {
        FinalizeChecklistCounts counts = passingCounts();
        counts.setUnresolvedCriticalExceptionCount(3);
        counts.setCapDetailMismatchCount(1);
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.COMPLETED, 8)));
        when(validationRunRepository.findFinalizeChecklistCounts(44L)).thenReturn(Optional.of(counts));

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "finalize-44"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_002);
                    assertThat(exception.getParams()).containsEntry("n", 2L);
                });

        verify(validationRunRepository, never()).finalizeIfCompleted(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidStateBeforeCheckingResults() {
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.RUNNING, 6)));

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "finalize-44"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_004));

        verify(validationRunRepository, never()).findFinalizeChecklistCounts(44L);
    }

    @Test
    void rejectsIdempotencyKeyOwnedByAnotherRun() {
        when(validationRunRepository.findValidationRunIdByFinalizeIdempotencyKey("shared-key"))
                .thenReturn(Optional.of(43L));

        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "shared-key"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_006));

        verify(validationRunRepository, never()).findByIdForUpdate(44L);
    }

    @Test
    void rejectsBlankIdempotencyKeyWhenHeaderIsProvided() {
        assertThatThrownBy(() -> service.finalizeRun(44L, 7L, "  "))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
    }

    @Test
    void finalizesWithoutOptionalIdempotencyKey() {
        when(validationRunRepository.findByIdForUpdate(44L)).thenReturn(Optional.of(run(ValidationRunStatus.COMPLETED, 8)));
        when(validationRunRepository.findFinalizeChecklistCounts(44L)).thenReturn(Optional.of(passingCounts()));
        when(validationRunRepository.finalizeIfCompleted(
                44L, 7L, null, ValidationRunStatus.COMPLETED, ValidationRunStatus.FINALIZED))
                .thenReturn(1);
        when(validationRunRepository.findFinalizationById(44L)).thenReturn(Optional.of(finalized(null)));

        assertThat(service.finalizeRun(44L, 7L, null).status()).isEqualTo("FINALIZED");
        verify(validationRunRepository, never()).findValidationRunIdByFinalizeIdempotencyKey(any());
    }

    @Test
    void requiresFinalizingUserIdEvenThoughLegacyColumnIsNullable() {
        assertThatThrownBy(() -> service.finalizeRun(44L, null, "finalize-44"))
                .isInstanceOfSatisfying(FgcBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
        verify(validationRunRepository, never()).findByIdForUpdate(any());
    }

    private FinalizeChecklistCounts passingCounts() {
        FinalizeChecklistCounts counts = new FinalizeChecklistCounts();
        counts.setValidationRunId(44L);
        counts.setValidationMonth(LocalDate.of(2026, 8, 1));
        return counts;
    }

    private ValidationRun run(ValidationRunStatus status, int currentStep) {
        ValidationRun run = ValidationRun.builder()
                .validationMonth(LocalDate.of(2026, 8, 1))
                .runNo(1)
                .runType("MONTHLY")
                .build();
        ReflectionTestUtils.setField(run, "validationRunId", 44L);
        ReflectionTestUtils.setField(run, "status", status);
        ReflectionTestUtils.setField(run, "currentStep", currentStep);
        return run;
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
