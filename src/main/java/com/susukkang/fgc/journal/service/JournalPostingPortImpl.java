package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.ScheduleJournalSourceRow;
import com.susukkang.fgc.journal.dto.TransactionJournalSourceRow;
import com.susukkang.fgc.journal.mapper.JournalMapper;
import com.susukkang.fgc.journal.mapper.JournalPostingSourceMapper;
import com.susukkang.fgc.validation.batch.contract.JournalPostingPort;
import com.susukkang.fgc.validation.batch.contract.JournalPostingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Step 6a(journalPostingStep) 구현체. validation_target(SELECTED)·검증월 범위의
 * schedule_line·commission_transaction을 읽어 4종(EXPECTED_INSURER_INCOME,
 * EXPECTED_FC_PAYOUT, ACTUAL_INSURER_STATEMENT, CONFIRMED_FC_PAYOUT) 분개를 저장하고
 * 기표(POSTED)한다(운영정책서 제38조 1차 범위).
 *
 * 06_배치_Step_협업계약.md:18 "기표 오류는 Step 실패" 원칙에 따라 원천 1건 처리 중 예외가
 * 나면 catch하지 않고 그대로 전파해 Step을 실패시킨다 — capCheckStep·arbitrageCheckStep처럼
 * 건별 skip으로 넘기지 않는다. JournalPersistenceService.saveDraft가 이미 멱등이라
 * (journal_type, source_entity_type, source_entity_id, revision_no) 원천은 재실행해도
 * 중복 기표되지 않는다.
 */
@Service
@RequiredArgsConstructor
public class JournalPostingPortImpl implements JournalPostingPort {

    private final JournalPostingSourceMapper sourceMapper;
    private final JournalEntryDraftService draftService;
    private final JournalPersistenceService persistenceService;
    private final JournalMapper journalMapper;

    @Override
    @Transactional
    public JournalPostingResult post(ValidationStepContext context) {
        Long validationRunId = context.validationRunId();
        LocalDate validationMonth = context.job().validationMonth();
        Long triggeredBy = context.job().triggeredBy();
        String requestId = context.job().requestId();

        long postedCount = 0;

        for (ScheduleJournalSourceRow row : sourceMapper.findExpectedInsurerIncomeSources(validationRunId, validationMonth)) {
            postAndCommit(draftService.draftExpectedInsurerIncome(
                    ExpectedInsurerIncomeJournalCommand.builder()
                            .scheduleLineId(row.getScheduleLineId())
                            .contractId(row.getContractId())
                            .policyVersionId(row.getPolicyVersionId())
                            .validationRunId(validationRunId)
                            .commissionItemId(row.getCommissionItemId())
                            .journalDate(row.getJournalDate())
                            .expectedAmount(row.getAmount())
                            .description(row.getDescription())
                            .build()), triggeredBy, requestId);
            postedCount++;
        }

        for (ScheduleJournalSourceRow row : sourceMapper.findExpectedFcPayoutSources(validationRunId, validationMonth)) {
            postAndCommit(draftService.draftExpectedFcPayout(
                    ExpectedFcPayoutJournalCommand.builder()
                            .scheduleLineId(row.getScheduleLineId())
                            .contractId(row.getContractId())
                            .policyVersionId(row.getPolicyVersionId())
                            .validationRunId(validationRunId)
                            .beneficiaryAgentId(row.getBeneficiaryAgentId())
                            .commissionItemId(row.getCommissionItemId())
                            .journalDate(row.getJournalDate())
                            .expectedAmount(row.getAmount())
                            .description(row.getDescription())
                            .build()), triggeredBy, requestId);
            postedCount++;
        }

        for (TransactionJournalSourceRow row : sourceMapper.findActualInsurerStatementSources(validationRunId, validationMonth)) {
            postAndCommit(draftService.draftActualInsurerStatement(
                    ActualInsurerStatementJournalCommand.builder()
                            .commissionTransactionId(row.getCommissionTransactionId())
                            .contractId(row.getContractId())
                            .policyVersionId(row.getPolicyVersionId())
                            .validationRunId(validationRunId)
                            .commissionItemId(row.getCommissionItemId())
                            .journalDate(row.getJournalDate())
                            .actualAmount(row.getAmount())
                            .description(row.getDescription())
                            .build()), triggeredBy, requestId);
            postedCount++;
        }

        for (TransactionJournalSourceRow row : sourceMapper.findConfirmedFcPayoutSources(validationRunId, validationMonth)) {
            postAndCommit(draftService.draftConfirmedFcPayout(
                    ConfirmedFcPayoutJournalCommand.builder()
                            .commissionTransactionId(row.getCommissionTransactionId())
                            .contractId(row.getContractId())
                            .policyVersionId(row.getPolicyVersionId())
                            .validationRunId(validationRunId)
                            .beneficiaryAgentId(row.getBeneficiaryAgentId())
                            .commissionItemId(row.getCommissionItemId())
                            .journalDate(row.getJournalDate())
                            .confirmedAmount(row.getAmount())
                            .description(row.getDescription())
                            .build()), triggeredBy, requestId);
            postedCount++;
        }

        return JournalPostingResult.success(postedCount);
    }

    /** 저장(멱등) 후 아직 DRAFT면 POSTED로 전이한다. 이미 POSTED/REVERSED면 손대지 않는다. */
    private void postAndCommit(JournalHeaderDraft draft, Long triggeredBy, String requestId) {
        JournalHeaderRow saved = persistenceService.saveDraft(draft, triggeredBy, requestId);
        if ("DRAFT".equals(saved.getStatus())) {
            journalMapper.markPosted(saved.getJournalHeaderId(), triggeredBy);
        }
    }
}
