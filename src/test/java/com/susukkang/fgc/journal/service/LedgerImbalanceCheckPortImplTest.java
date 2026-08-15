package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class LedgerImbalanceCheckPortImplTest {

    @Mock
    private JournalImbalanceMapper journalImbalanceMapper;

    private ValidationStepContext newContext(long validationRunId) {
        ValidationJobContext job = new ValidationJobContext(
                LocalDate.of(2026, 8, 1), 1L, ValidationRunType.MONTHLY, 3L, "req-98");
        return new ValidationStepContext(validationRunId, job);
    }

    @Test
    void checkReturnsInspectedAndImbalanceCountsFromMapper() {
        given(journalImbalanceMapper.countJournals(42L)).willReturn(10L);
        given(journalImbalanceMapper.countImbalances(42L)).willReturn(2L);

        LedgerImbalanceCheckPortImpl port = new LedgerImbalanceCheckPortImpl(journalImbalanceMapper);
        LedgerImbalanceResult result = port.check(newContext(42L));

        assertThat(result.inspectedJournalCount()).isEqualTo(10L);
        assertThat(result.imbalanceCount()).isEqualTo(2L);
    }

    @Test
    void checkReturnsZeroImbalanceWhenAllJournalsAreBalanced() {
        given(journalImbalanceMapper.countJournals(42L)).willReturn(5L);
        given(journalImbalanceMapper.countImbalances(42L)).willReturn(0L);

        LedgerImbalanceCheckPortImpl port = new LedgerImbalanceCheckPortImpl(journalImbalanceMapper);
        LedgerImbalanceResult result = port.check(newContext(42L));

        assertThat(result.imbalanceCount()).isZero();
    }
}
