package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.event.SettlementPosted;
import com.susukkang.fgc.journal.repository.JournalQueryRepository;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceCheckPort;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class LedgerImbalanceCheckPortImpl implements LedgerImbalanceCheckPort {

    private final JournalQueryRepository journalQueryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public LedgerImbalanceCheckPortImpl(JournalQueryRepository journalQueryRepository,
                                         ApplicationEventPublisher eventPublisher) {
        this.journalQueryRepository = journalQueryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public LedgerImbalanceResult check(ValidationStepContext context) {
        long imbalanceCount = journalQueryRepository.countImbalances(context.validationRunId());
        long inspectedJournalCount = journalQueryRepository.countJournals(context.validationRunId());

        // IF-EVT-06: Step 6이 불균형 0건으로 끝났을 때만 발행한다 — imbalanceCheckStep이
        // 여기서 예외를 던지면 Step 자체가 FAILED로 끝나 "완료"가 아니다.
        if (imbalanceCount == 0) {
            eventPublisher.publishEvent(
                    new SettlementPosted(context.validationRunId(), inspectedJournalCount, imbalanceCount));
        }

        return new LedgerImbalanceResult(inspectedJournalCount, imbalanceCount);
    }
}
