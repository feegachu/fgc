package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.journal.event.SettlementPosted;
import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LedgerImbalanceCheckPortImplTest {

    @Mock
    private JournalImbalanceMapper journalImbalanceMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ValidationStepContext newContext(long validationRunId) {
        ValidationJobContext job = new ValidationJobContext(
                LocalDate.of(2026, 8, 1), 1L, ValidationRunType.MONTHLY, 3L, "req-98");
        return new ValidationStepContext(validationRunId, job);
    }

    @Test
    void checkReturnsInspectedAndImbalanceCountsFromMapper() {
        given(journalImbalanceMapper.countJournals(42L)).willReturn(10L);
        given(journalImbalanceMapper.countImbalances(42L)).willReturn(2L);

        LedgerImbalanceCheckPortImpl port = new LedgerImbalanceCheckPortImpl(journalImbalanceMapper, eventPublisher);
        LedgerImbalanceResult result = port.check(newContext(42L));

        assertThat(result.inspectedJournalCount()).isEqualTo(10L);
        assertThat(result.imbalanceCount()).isEqualTo(2L);
    }

    @Test
    // 불균형이 있으면 IF-EVT-06(SettlementPosted)을 발행하지 않는다 — Step6이 실패로 끝나기 때문
    void checkDoesNotPublishSettlementPostedWhenImbalanceExists() {
        given(journalImbalanceMapper.countJournals(42L)).willReturn(10L);
        given(journalImbalanceMapper.countImbalances(42L)).willReturn(2L);

        LedgerImbalanceCheckPortImpl port = new LedgerImbalanceCheckPortImpl(journalImbalanceMapper, eventPublisher);
        port.check(newContext(42L));

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void checkReturnsZeroImbalanceWhenAllJournalsAreBalanced() {
        given(journalImbalanceMapper.countJournals(42L)).willReturn(5L);
        given(journalImbalanceMapper.countImbalances(42L)).willReturn(0L);

        LedgerImbalanceCheckPortImpl port = new LedgerImbalanceCheckPortImpl(journalImbalanceMapper, eventPublisher);
        LedgerImbalanceResult result = port.check(newContext(42L));

        assertThat(result.imbalanceCount()).isZero();
    }

    @Test
    // 불균형 0건이면 IF-EVT-06(SettlementPosted)을 정확한 페이로드로 발행한다
    void checkPublishesSettlementPostedWhenBalanced() {
        given(journalImbalanceMapper.countJournals(42L)).willReturn(5L);
        given(journalImbalanceMapper.countImbalances(42L)).willReturn(0L);

        LedgerImbalanceCheckPortImpl port = new LedgerImbalanceCheckPortImpl(journalImbalanceMapper, eventPublisher);
        port.check(newContext(42L));

        verify(eventPublisher).publishEvent(new SettlementPosted(42L, 5L, 0L));
    }
}
