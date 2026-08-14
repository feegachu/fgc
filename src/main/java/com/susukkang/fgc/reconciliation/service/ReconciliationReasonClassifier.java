package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.domain.ReconciliationReasonCode;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.ReconciliationCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassification;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassificationContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * FUN-049-01 순서가 고정된 단일 분류 정책.
 * 같은 입력은 항상 같은 주 사유와 정렬된 보조 사유를 만든다.
 */
@Component
public class ReconciliationReasonClassifier {

    public ReconciliationClassification classify(
            ReconciliationCandidate candidate,
            ReconciliationClassificationContext context
    ) {
        Objects.requireNonNull(candidate, "candidate is required");
        ReconciliationClassificationContext safeContext = context == null
                ? new ReconciliationClassificationContext()
                : context;

        LinkedHashSet<ReconciliationReasonCode> signals = new LinkedHashSet<>();
        addContextSignals(signals, safeContext);
        ReconciliationReasonCode.fromCode(candidate.primaryReasonCode()).ifPresent(signals::add);
        candidate.secondaryReasonCodes().stream()
                .map(ReconciliationReasonCode::fromCode)
                .flatMap(java.util.Optional::stream)
                .forEach(signals::add);

        if (signals.size() > 1) {
            signals.remove(ReconciliationReasonCode.MATCHED);
        }

        if (signals.isEmpty()) {
            signals.add(candidate.resultType() == ReconciliationResultType.MATCHED
                    ? ReconciliationReasonCode.MATCHED
                    : ReconciliationReasonCode.UNKNOWN);
        } else if (signals.size() == 1
                && signals.contains(ReconciliationReasonCode.REVIEW_REQUIRED)
                && candidate.primaryReasonCode() == null) {
            signals.clear();
            signals.add(ReconciliationReasonCode.UNKNOWN);
        }

        List<ReconciliationReasonCode> ordered = signals.stream()
                .sorted(Comparator.comparingInt(ReconciliationReasonCode::priority))
                .toList();
        ReconciliationReasonCode primary = ordered.getFirst();
        List<String> secondary = ordered.stream().skip(1).map(Enum::name).toList();
        return new ReconciliationClassification(primary.resultType(), primary.name(), secondary);
    }

    private static void addContextSignals(
            LinkedHashSet<ReconciliationReasonCode> signals,
            ReconciliationClassificationContext context
    ) {
        if (context.isJournalImbalance()) {
            signals.add(ReconciliationReasonCode.JOURNAL_IMBALANCE);
        }
        if (context.isInvalidContractPayment()) {
            signals.add(ReconciliationReasonCode.INVALID_CONTRACT_PAYMENT);
        }
        if (context.isPolicyVersionError()) {
            signals.add(ReconciliationReasonCode.POLICY_VERSION_ERROR);
        }
        if (context.isOrganizationMismatch()) {
            signals.add(ReconciliationReasonCode.ORGANIZATION_MISMATCH);
        }
    }
}
