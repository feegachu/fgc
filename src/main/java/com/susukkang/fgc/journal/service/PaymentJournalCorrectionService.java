package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalCorrectionHeaderRow;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import com.susukkang.fgc.transaction.repository.PaymentJournalCorrectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 실제 원천 분개에 한해 지급 취소/대체를 원장 정정 트랜잭션에 참여시킨다. */
@Service
@RequiredArgsConstructor
public class PaymentJournalCorrectionService {
    private final PaymentJournalCorrectionRepository repository;
    private final CommissionPaymentService payments;
    private final AuditLogService audit;

    public boolean supports(JournalCorrectionHeaderRow original) {
        return "COMMISSION_TRANSACTION".equals(original.getSourceEntityType())
                && (JournalType.CONFIRMED_FC_PAYOUT.name().equals(original.getJournalType())
                || JournalType.ACTUAL_INSURER_STATEMENT.name().equals(original.getJournalType()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public JournalHeaderDraft correct(JournalCorrectionHeaderRow original, JournalHeaderDraft draft,
                                      String groupKey, String reason, String evidence, Long actor) {
        if (!supports(original)) return draft;
        Long paymentId;
        try {
            paymentId = Long.valueOf(original.getSourceEntityId());
        } catch (NumberFormatException ex) {
            throw invalid("sourceEntityId");
        }
        if (!repository.lockPayment(paymentId)) throw invalid("sourceEntityId");
        CommissionPaymentResponse old = payments.get(paymentId);
        if (old.status() != CommissionPaymentStatus.CONFIRMED || !"PAYMENT".equals(old.cashflowType())
                || old.attributions().size() != 1 || old.attributions().getFirst().contractId() == null
                || !Objects.equals(old.attributions().getFirst().contractId(), original.getContractId())) {
            throw invalid("paymentCorrectionSource");
        }
        boolean payout = JournalType.CONFIRMED_FC_PAYOUT.name().equals(original.getJournalType());
        if (payout != (old.paymentStage() == com.susukkang.fgc.common.code.PaymentStage.GA_TO_FC)) {
            throw invalid("paymentStage");
        }
        BigDecimal correctedAmount = draft == null ? null : amount(draft, old, payout);
        // 원본 취소를 먼저 적용해 대체 지급 확정의 누적 한도에서 이중 집계를 피한다.
        if (repository.cancel(paymentId) != 1) throw invalid("paymentStatus");
        CommissionPaymentResponse replacement = null;
        if (draft != null) {
            var a = old.attributions().getFirst();
            replacement = payments.create(new CommissionPaymentCreateRequest(
                    "GA_MANUAL_PAYMENT", "JOURNAL_CORRECTION:" + groupKey, old.contractId(), old.agentId(),
                    old.commissionItemId(), correctedAmount, old.settlementMonth(), old.cashflowType(),
                    old.scheduledPaymentDate(), old.paymentStage(), old.allocationPolicyVersion(),
                    List.of(new CommissionPaymentAttributionRequest(a.contractId(), a.attributionDate(),
                            correctedAmount, a.inclusionDecisionStatus(), a.exclusionType(),
                            (a.inclusionDecisionReason() == null || a.inclusionDecisionReason().isBlank())
                                    ? reason : a.inclusionDecisionReason(), a.allocationBasis(), a.evidenceRef(), a.attributionMethod())),
                    evidence == null ? old.evidenceRef() : evidence, reason));
            repository.copySourceMetadata(paymentId, replacement.paymentId());
        }
        repository.insertLink(groupKey, paymentId, replacement == null ? null : replacement.paymentId());
        if (replacement != null) payments.confirmJournalCorrection(replacement.paymentId(), groupKey);
        audit.record(AuditLogService.AuditEvent.builder().actionCode("PAYMENT_CANCELLED")
                .entityType("COMMISSION_TRANSACTION").entityId(paymentId.toString()).userId(actor)
                .before(old).after(Map.of("status", "CANCELLED", "correctionGroupKey", groupKey,
                        "replacementPaymentId", replacement == null ? "" : replacement.paymentId()))
                .reason(reason).build());
        // 대체 지급의 기본 분개(revision 1)로 저장해야 월 검증 재실행도 멱등성을 지킨다.
        return draft == null ? null : draft.toBuilder().sourceEntityId(replacement.paymentId().toString())
                .revisionNo(1).build();
    }

    private BigDecimal amount(JournalHeaderDraft draft, CommissionPaymentResponse old, boolean payout) {
        var debit = payout ? JournalAccountCode.CONFIRMED_PAYOUT_EXPENSE : JournalAccountCode.ACTUAL_RECEIVABLE;
        var credit = payout ? JournalAccountCode.CONFIRMED_PAYOUT_PAYABLE : JournalAccountCode.ACTUAL_INCOME;
        if (draft.getLines().size() != 2) throw invalid("paymentCorrectionLines");
        JournalLineDraft debitLine = draft.getLines().stream().filter(l -> l.getAccountCode() == debit)
                .findFirst().orElseThrow(() -> invalid("paymentCorrectionAccounts"));
        JournalLineDraft creditLine = draft.getLines().stream().filter(l -> l.getAccountCode() == credit)
                .findFirst().orElseThrow(() -> invalid("paymentCorrectionAccounts"));
        for (var line : draft.getLines()) {
            if (!Objects.equals(line.getContractId(), old.attributions().getFirst().contractId())
                    || !Objects.equals(line.getCommissionItemId(), old.commissionItemId())
                    || line.getPaymentStage() != old.paymentStage()
                    || !Objects.equals(line.getAgentId(), payout ? old.agentId() : null)) {
                throw invalid("paymentCorrectionAttribution");
            }
        }
        if (debitLine.getDebitAmount().signum() <= 0 || debitLine.getCreditAmount().signum() != 0
                || creditLine.getDebitAmount().signum() != 0
                || debitLine.getDebitAmount().compareTo(creditLine.getCreditAmount()) != 0) {
            throw invalid("paymentCorrectionAmount");
        }
        if (debitLine.getDebitAmount().stripTrailingZeros().scale() > 0) {
            throw invalid("paymentCorrectionAmount");
        }
        return debitLine.getDebitAmount();
    }

    private FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("field", field));
    }
}
