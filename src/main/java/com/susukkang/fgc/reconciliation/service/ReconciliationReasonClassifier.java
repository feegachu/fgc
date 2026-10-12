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
import java.util.stream.Stream;

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

        // 2026-10-12 yslee - #518 검토필요로 보류된 대상 계약의 "예상 내역 없음"을 검토 필요로 낮춘다.
        // 기존 코드: 예상 스케줄이 없는 지급은 대상 선별 결과와 무관하게 EXPECTED_MISSING(HIGH)이었다.
        // 문제: 보류 대상은 스케줄 재생성 등 하위 검증을 받지 않아(FUN-042, selectScheduleStates는 SELECTED만)
        //       예상이 없는 원인이 "보류"일 수 있는데 확정 불일치로 단정했다(시드 FGL03 8월).
        // 개선: 이 신호만 REVIEW_REQUIRED(WARNING)로 바꾸고 EXPECTED_MISSING은 보조 사유로 남긴다.
        //       우선순위가 더 높은 신호(비유효 계약·분개 불균형·정책 버전·중복)는 그대로 주 사유가 된다.
        boolean expectedMissingHeldForReview = safeContext.isTargetReviewRequired()
                && signals.remove(ReconciliationReasonCode.EXPECTED_MISSING);
        if (expectedMissingHeldForReview) {
            signals.add(ReconciliationReasonCode.REVIEW_REQUIRED);
        }

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
        List<String> secondary = Stream.concat(
                        ordered.stream().skip(1),
                        expectedMissingHeldForReview ? Stream.of(ReconciliationReasonCode.EXPECTED_MISSING) : Stream.empty())
                .sorted(Comparator.comparingInt(ReconciliationReasonCode::priority))
                .map(Enum::name)
                .toList();
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
        if (context.isMissingJournalEvidence()) {
            signals.add(ReconciliationReasonCode.UNKNOWN);
        }
    }
}
