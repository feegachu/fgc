package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class JournalEntryDraftServiceImpl implements JournalEntryDraftService {

    @Override
    public JournalHeaderDraft draftExpectedInsurerIncome(ExpectedInsurerIncomeJournalCommand command) {
        // 1. 금액 검증
        if (command.getExpectedAmount() == null || command.getExpectedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("expectedAmount must be positive: " + command.getExpectedAmount());
        }

        // 2. 차변 줄 (EXPECTED_RECEIVABLE, lineNo=1)
        JournalLineDraft debitLine = JournalLineDraft.builder()
                .lineNo(1)
                .accountCode(JournalAccountCode.EXPECTED_RECEIVABLE)
                .debitAmount(command.getExpectedAmount())
                .creditAmount(BigDecimal.ZERO)
                .contractId(command.getContractId())
                .agentId(null)
                .paymentStage(PaymentStage.INSURER_TO_GA)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();
        // 3. 대변 줄 (EXPECTED_INCOME, lineNo=2)
        JournalLineDraft creditLine = JournalLineDraft.builder()
                .lineNo(2)
                .accountCode(JournalAccountCode.EXPECTED_INCOME)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(command.getExpectedAmount())
                .contractId(command.getContractId())
                .agentId(null)
                .paymentStage(PaymentStage.INSURER_TO_GA)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();


        // 4. 헤더 조립
        return JournalHeaderDraft.builder()
                .journalType(JournalType.EXPECTED_INSURER_INCOME)
                .journalDate(command.getJournalDate())
                .sourceEntityType("SCHEDULE_LINE")
                .sourceEntityId(String.valueOf(command.getScheduleLineId()))
                .revisionNo(1)
                .validationRunId(command.getValidationRunId())
                .contractId(command.getContractId())
                .policyVersionId(command.getPolicyVersionId())
                .description(command.getDescription())
                .lines(List.of(debitLine, creditLine))
                .build();
    }

    @Override
    public JournalHeaderDraft draftActualInsurerStatement(ActualInsurerStatementJournalCommand command) {
        if (command.getActualAmount() == null || command.getActualAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("actualAmount must be positive: " + command.getActualAmount());
        }

        JournalLineDraft debitLine = JournalLineDraft.builder()
                .lineNo(1)
                .accountCode(JournalAccountCode.ACTUAL_RECEIVABLE)
                .debitAmount(command.getActualAmount())
                .creditAmount(BigDecimal.ZERO)
                .contractId(command.getContractId())
                .agentId(null)
                .paymentStage(PaymentStage.INSURER_TO_GA)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();

        JournalLineDraft creditLine = JournalLineDraft.builder()
                .lineNo(2)
                .accountCode(JournalAccountCode.ACTUAL_INCOME)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(command.getActualAmount())
                .contractId(command.getContractId())
                .agentId(null)
                .paymentStage(PaymentStage.INSURER_TO_GA)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();

        return JournalHeaderDraft.builder()
                .journalType(JournalType.ACTUAL_INSURER_STATEMENT)
                .journalDate(command.getJournalDate())
                .sourceEntityType("COMMISSION_TRANSACTION")
                .sourceEntityId(String.valueOf(command.getCommissionTransactionId()))
                .revisionNo(1)
                .validationRunId(command.getValidationRunId())
                .contractId(command.getContractId())
                .policyVersionId(command.getPolicyVersionId())
                .description(command.getDescription())
                .lines(List.of(debitLine, creditLine))
                .build();
    }

    @Override
    public JournalHeaderDraft draftExpectedFcPayout(ExpectedFcPayoutJournalCommand command) {
        if (command.getExpectedAmount() == null || command.getExpectedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("expectedAmount must be positive: " + command.getExpectedAmount());
        }

        JournalLineDraft debitLine = JournalLineDraft.builder()
                .lineNo(1)
                .accountCode(JournalAccountCode.EXPECTED_PAYOUT_EXPENSE)
                .debitAmount(command.getExpectedAmount())
                .creditAmount(BigDecimal.ZERO)
                .contractId(command.getContractId())
                .agentId(command.getBeneficiaryAgentId())
                .paymentStage(PaymentStage.GA_TO_FC)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();

        JournalLineDraft creditLine = JournalLineDraft.builder()
                .lineNo(2)
                .accountCode(JournalAccountCode.EXPECTED_PAYOUT_PAYABLE)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(command.getExpectedAmount())
                .contractId(command.getContractId())
                .agentId(command.getBeneficiaryAgentId())
                .paymentStage(PaymentStage.GA_TO_FC)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();

        return JournalHeaderDraft.builder()
                .journalType(JournalType.EXPECTED_FC_PAYOUT)
                .journalDate(command.getJournalDate())
                .sourceEntityType("SCHEDULE_LINE")
                .sourceEntityId(String.valueOf(command.getScheduleLineId()))
                .revisionNo(1)
                .validationRunId(command.getValidationRunId())
                .contractId(command.getContractId())
                .policyVersionId(command.getPolicyVersionId())
                .description(command.getDescription())
                .lines(List.of(debitLine, creditLine))
                .build();
    }

    @Override
    public JournalHeaderDraft draftConfirmedFcPayout(ConfirmedFcPayoutJournalCommand command) {
        if (command.getConfirmedAmount() == null || command.getConfirmedAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("confirmedAmount must be positive: " + command.getConfirmedAmount());
        }

        JournalLineDraft debitLine = JournalLineDraft.builder()
                .lineNo(1)
                .accountCode(JournalAccountCode.CONFIRMED_PAYOUT_EXPENSE)
                .debitAmount(command.getConfirmedAmount())
                .creditAmount(BigDecimal.ZERO)
                .contractId(command.getContractId())
                .agentId(command.getBeneficiaryAgentId())
                .paymentStage(PaymentStage.GA_TO_FC)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();

        JournalLineDraft creditLine = JournalLineDraft.builder()
                .lineNo(2)
                .accountCode(JournalAccountCode.CONFIRMED_PAYOUT_PAYABLE)
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(command.getConfirmedAmount())
                .contractId(command.getContractId())
                .agentId(command.getBeneficiaryAgentId())
                .paymentStage(PaymentStage.GA_TO_FC)
                .commissionItemId(command.getCommissionItemId())
                .memo(command.getDescription())
                .build();

        return JournalHeaderDraft.builder()
                .journalType(JournalType.CONFIRMED_FC_PAYOUT)
                .journalDate(command.getJournalDate())
                .sourceEntityType("COMMISSION_TRANSACTION")
                .sourceEntityId(String.valueOf(command.getCommissionTransactionId()))
                .revisionNo(1)
                .validationRunId(command.getValidationRunId())
                .contractId(command.getContractId())
                .policyVersionId(command.getPolicyVersionId())
                .description(command.getDescription())
                .lines(List.of(debitLine, creditLine))
                .build();
    }
}
