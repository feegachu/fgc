package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.mapper.JournalImbalanceMapper;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceCheckPort;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.springframework.stereotype.Service;

@Service
public class LedgerImbalanceCheckPortImpl implements LedgerImbalanceCheckPort {

    private final JournalImbalanceMapper journalImbalanceMapper;

    public LedgerImbalanceCheckPortImpl(JournalImbalanceMapper journalImbalanceMapper) {
        this.journalImbalanceMapper = journalImbalanceMapper;
    }

    @Override
    public LedgerImbalanceResult check(ValidationStepContext context) {
        Long imbalanceCount = journalImbalanceMapper.countImbalances(context.validationRunId());
        Long inspectedJournalCount = journalImbalanceMapper.countJournals(context.validationRunId());

        return new LedgerImbalanceResult(inspectedJournalCount,imbalanceCount);
    }
}
