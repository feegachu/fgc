package com.susukkang.fgc.exceptioncase.service;

import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.exceptioncase.dto.ExceptionCaseActionTarget;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionPaymentRepository;
import com.susukkang.fgc.reconciliation.service.PaymentCorrectionReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExceptionScheduleRecheckTest {
    @Mock ExceptionPaymentRepository repository;
    @Mock ExceptionCaseRepository cases;
    @Mock ExceptionCaseService exceptions;
    @Mock CapCheckService capChecks;
    @Mock PaymentCorrectionReconciliationService reconciliation;
    @Mock com.susukkang.fgc.journal.service.JournalPostingPortImpl scheduleJournals;
    @InjectMocks ExceptionPaymentCorrectionService service;

    private final ExceptionPaymentRepository.Source source = new ExceptionPaymentRepository.Source(
            18L, "CAP_VIOLATION", 55L, "CAP_CHECK", "64", 25L);
    private final ExceptionPaymentRepository.CapScope scope = new ExceptionPaymentRepository.CapScope(
            64L, 55L, PaymentStage.GA_TO_FC, LocalDate.of(2026, 10, 31), true, 76L, 1L);

    @BeforeEach
    void setup() {
        when(repository.source(18L)).thenReturn(source);
        when(repository.capScope(18L)).thenReturn(scope);
        when(cases.findByIdForUpdate(18L)).thenReturn(new ExceptionCaseActionTarget(
                18L, "CAP_VIOLATION", ExceptionStatus.IN_REVIEW, 1L));
        when(capChecks.findDetail(64L)).thenReturn(Optional.empty());
    }

    @Test
    void correctedScheduleRefreshesPaymentEvidenceBeforeResolvingCapException() {
        result(CapResultStatus.NORMAL);
        when(repository.scheduleReconciliationRuns(source, scope)).thenReturn(List.of(91L));

        assertThat(service.recheckSchedule(18L, 1L, "settle01").resolved()).isTrue();

        var order = inOrder(scheduleJournals, reconciliation, exceptions);
        order.verify(scheduleJournals).postExpectedForContract(55L, 25L, LocalDate.of(2026, 10, 1), 1L);
        order.verify(reconciliation).refresh(List.of(91L), "EXCP-SCHEDULE-18", 1L);
        order.verify(exceptions).action(eq(18L), argThat(a -> a.actionType() == ExceptionActionType.CORRECT),
                eq(1L), eq("settle01"));
    }

    @Test
    void stillInvalidScheduleDoesNotRefreshOrResolveTheException() {
        result(CapResultStatus.VIOLATION);

        assertThat(service.recheckSchedule(18L, 1L, "settle01").resolved()).isFalse();

        verify(repository, never()).scheduleReconciliationRuns(any(), any());
        verifyNoInteractions(reconciliation, scheduleJournals);
        verify(exceptions, never()).action(anyLong(), any(), anyLong(), anyString());
    }

    private void result(CapResultStatus status) {
        var limit = new BigDecimal("1200000");
        var included = new BigDecimal(status == CapResultStatus.NORMAL ? "895000" : "1390000");
        var result = new CapCalculationResult(55L, PaymentStage.GA_TO_FC, CapCheckKind.MANUAL,
                scope.asOfDate(), 1L, 1L, new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO,
                limit, included, limit.subtract(included), new BigDecimal("75"), status, List.of(), Map.of());
        when(capChecks.calculateAndSave(any())).thenReturn(new CapCheckSaveResult(99L, result));
    }
}
