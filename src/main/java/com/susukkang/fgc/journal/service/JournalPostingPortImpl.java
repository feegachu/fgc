package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.ScheduleJournalSourceRow;
import com.susukkang.fgc.journal.dto.TransactionJournalSourceRow;
import com.susukkang.fgc.journal.repository.JournalHeaderRepository;
import com.susukkang.fgc.journal.repository.JournalPostingSourceQueryRepository;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.JournalPostingPort;
import com.susukkang.fgc.validation.batch.contract.JournalPostingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;

/**
 * Step 6a(journalPostingStep) 구현체. validation_target(SELECTED)·검증월 범위의
 * schedule_line·commission_transaction을 읽어 4종(EXPECTED_INSURER_INCOME,
 * EXPECTED_FC_PAYOUT, ACTUAL_INSURER_STATEMENT, CONFIRMED_FC_PAYOUT) 분개를 저장하고
 * 기표(POSTED)한다(운영정책서 제38조 1차 범위).
 *
 * 06_배치_Step_협업계약.md:18 "기표 오류는 Step 실패" 원칙에 따라 원천 1건 처리 중 예외가
 * 나면 catch하지 않고 그대로 전파해 Step을 실패시킨다 — capCheckStep·arbitrageCheckStep처럼
 * 건별 skip으로 넘기지 않는다. 건별 커밋한 초안은 실패 후 재시작에서도 멱등이므로
 * (journal_type, source_entity_type, source_entity_id, revision_no) 원천은 재실행해도
 * 중복 기표되지 않는다 — 이미 POSTED인 원천은 postedJournalCount(신규 기표 건수)가 아니라
 * skips(ALREADY_POSTED)로 집계한다(코드리뷰 반영: 재실행 시 postedJournalCount가 항상
 * 원천 건수를 그대로 보고하면 배치 write count·감사 로그가 실제 신규 기표량과 어긋난다).
 */
@Service
@RequiredArgsConstructor
public class JournalPostingPortImpl implements JournalPostingPort {

    private static final String ALREADY_POSTED_REASON = "ALREADY_POSTED";

    private final JournalPostingSourceQueryRepository sourceRepository;
    private final JournalEntryDraftService draftService;
    private final JournalPersistenceService persistenceService;
    private final JournalHeaderRepository journalHeaderRepository;
    private final JournalCorrectionService journalCorrections;

    @Override
    @Transactional
    public JournalPostingResult post(ValidationStepContext context) {
        Long validationRunId = context.validationRunId();
        LocalDate validationMonth = context.job().validationMonth();
        Long triggeredBy = context.job().triggeredBy();
        String requestId = context.job().requestId();

        long newlyPostedCount = 0;
        List<ContractSkip> skips = new ArrayList<>();

        for (ScheduleJournalSourceRow row : sourceRepository.findExpectedInsurerIncomeSources(validationRunId, validationMonth)) {
            JournalHeaderDraft draft = expectedInsurerDraft(row, validationRunId);
            newlyPostedCount += postAndCommit(draft, row.getContractId(), triggeredBy, requestId, skips);
        }

        for (ScheduleJournalSourceRow row : sourceRepository.findExpectedFcPayoutSources(validationRunId, validationMonth)) {
            JournalHeaderDraft draft = expectedFcDraft(row, validationRunId);
            newlyPostedCount += postAndCommit(draft, row.getContractId(), triggeredBy, requestId, skips);
        }

        for (TransactionJournalSourceRow row : sourceRepository.findActualInsurerStatementSources(validationRunId, validationMonth)) {
            JournalHeaderDraft draft = draftService.draftActualInsurerStatement(
                    ActualInsurerStatementJournalCommand.builder()
                            .commissionTransactionId(row.getCommissionTransactionId())
                            .contractId(row.getContractId())
                            .policyVersionId(row.getPolicyVersionId())
                            .validationRunId(validationRunId)
                            .commissionItemId(row.getCommissionItemId())
                            .journalDate(row.getJournalDate())
                            .actualAmount(row.getAmount())
                            .description(row.getDescription())
                            .build());
            newlyPostedCount += postAndCommit(draft, row.getContractId(), triggeredBy, requestId, skips);
        }

        for (TransactionJournalSourceRow row : sourceRepository.findConfirmedFcPayoutSources(validationRunId, validationMonth)) {
            JournalHeaderDraft draft = draftService.draftConfirmedFcPayout(
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
                            .build());
            newlyPostedCount += postAndCommit(draft, row.getContractId(), triggeredBy, requestId, skips);
        }

        return new JournalPostingResult(newlyPostedCount, skips.size(), 0, skips);
    }

    /** 스케줄 정정 후 같은 계약·검증월의 새 예상 분개만 같은 트랜잭션에서 기표한다. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void postExpectedForContract(Long contractId, Long validationRunId, LocalDate month, Long actor) {
        if (validationRunId == null) return;
        sourceRepository.lockExpectedJournalValidationRuns(contractId, validationRunId, month);
        String requestId = "EXCP-SCHEDULE-" + validationRunId + "-" + contractId;
        var groups = new HashMap<String, String>();
        for (Object[] source : sourceRepository.findSupersededExpectedJournals(contractId, month)) {
            var reversal = journalCorrections.reverse(new ReverseJournalCommand(((Number) source[0]).longValue(),
                    "예상 스케줄 재생성에 따른 정정", requestId, actor));
            if (source[1] != null) groups.put(source[1].toString(), reversal.correctionGroupKey());
        }
        for (var row : sourceRepository.findExpectedInsurerIncomeSources(validationRunId, month)) {
            if (contractId.equals(row.getContractId())) {
                postCorrectionDraft(expectedInsurerDraft(row, validationRunId), actor, requestId, groups.get(row.getScheduleLineId().toString()));
            }
        }
        for (var row : sourceRepository.findExpectedFcPayoutSources(validationRunId, month)) {
            if (contractId.equals(row.getContractId())) {
                postCorrectionDraft(expectedFcDraft(row, validationRunId), actor, requestId, groups.get(row.getScheduleLineId().toString()));
            }
        }
    }

    private void postCorrectionDraft(JournalHeaderDraft draft, Long actor, String requestId, String groupKey) {
        var saved = persistenceService.saveDraftInCurrentTransaction(draft, actor, requestId);
        if ("DRAFT".equals(saved.getStatus())) {
            if (groupKey != null && journalHeaderRepository.linkReplacementGroup(saved.getJournalHeaderId(), groupKey) != 1) {
                throw new IllegalStateException("대체 예상 분개의 정정 연결을 저장하지 못했습니다.");
            }
            journalHeaderRepository.markPosted(saved.getJournalHeaderId(), actor);
        }
    }

    private JournalHeaderDraft expectedInsurerDraft(ScheduleJournalSourceRow row, Long validationRunId) {
        return draftService.draftExpectedInsurerIncome(
                    ExpectedInsurerIncomeJournalCommand.builder()
                            .scheduleLineId(row.getScheduleLineId())
                            .contractId(row.getContractId())
                            .policyVersionId(row.getPolicyVersionId())
                            .validationRunId(validationRunId)
                            .commissionItemId(row.getCommissionItemId())
                            .journalDate(row.getJournalDate())
                            .expectedAmount(row.getAmount())
                            .description(row.getDescription())
                            .build());
    }

    private JournalHeaderDraft expectedFcDraft(ScheduleJournalSourceRow row, Long validationRunId) {
        return draftService.draftExpectedFcPayout(
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
                            .build());
    }

    /**
     * 저장(멱등) 후 아직 DRAFT면 POSTED로 전이한다. 이미 처리된 원천은 skip으로 기록한다.
     * @return 이번 호출로 새로 POSTED가 됐으면 1, 이전에 처리됐으면 0
     */
    private long postAndCommit(JournalHeaderDraft draft, Long contractId, Long triggeredBy,
                                String requestId, List<ContractSkip> skips) {
        JournalHeaderRow saved = persistenceService.saveDraft(draft, triggeredBy, requestId);
        if ("DRAFT".equals(saved.getStatus())
                && journalHeaderRepository.markPosted(saved.getJournalHeaderId(), triggeredBy) == 1) {
            return 1;
        }
        skips.add(new ContractSkip(contractId, ALREADY_POSTED_REASON,
                "journalHeaderId=" + saved.getJournalHeaderId() + " statusAtRead=" + saved.getStatus()));
        return 0;
    }
}
