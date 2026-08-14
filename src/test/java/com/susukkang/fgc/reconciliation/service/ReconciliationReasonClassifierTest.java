package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.ReconciliationCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassification;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassificationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ReconciliationReasonClassifierTest {

    private final ReconciliationReasonClassifier classifier = new ReconciliationReasonClassifier();

    @Test
    void 단일_원인은_대응하는_결과_유형과_주사유로_분류한다() {
        for (ReconciliationResultType type : ReconciliationResultType.values()) {
            ReconciliationCandidate candidate = candidate(type, type.name(), List.of());

            ReconciliationClassification result = classifier.classify(
                    candidate, new ReconciliationClassificationContext());

            assertThat(result.primaryReasonCode()).isEqualTo(type.name());
            assertThat(result.secondaryReasonCodes()).isEmpty();
        }
    }

    @Test
    void 복합_원인은_고정_우선순위로_주사유와_보조사유를_정렬하고_중복을_제거한다() {
        ReconciliationCandidate candidate = candidate(
                ReconciliationResultType.AMOUNT_DIFFERENCE,
                "AMOUNT_DIFFERENCE",
                List.of("AGENT_MISMATCH", "DUPLICATE", "AMOUNT_DIFFERENCE"));
        ReconciliationClassificationContext context = new ReconciliationClassificationContext();
        context.setOrganizationMismatch(true);
        context.setPolicyVersionError(true);
        context.setJournalImbalance(true);

        ReconciliationClassification result = classifier.classify(candidate, context);

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.JOURNAL_IMBALANCE);
        assertThat(result.primaryReasonCode()).isEqualTo("JOURNAL_IMBALANCE");
        assertThat(result.secondaryReasonCodes()).containsExactly(
                "POLICY_VERSION_ERROR", "DUPLICATE", "ORGANIZATION_MISMATCH",
                "AGENT_MISMATCH", "AMOUNT_DIFFERENCE");
    }

    @Test
    void 판별_근거가_없는_불일치는_UNKNOWN_사유의_REVIEW_REQUIRED로_남긴다() {
        ReconciliationCandidate candidate = candidate(
                ReconciliationResultType.REVIEW_REQUIRED, null, List.of("NOT_SUPPORTED"));

        ReconciliationClassification result = classifier.classify(
                candidate, new ReconciliationClassificationContext());

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.REVIEW_REQUIRED);
        assertThat(result.primaryReasonCode()).isEqualTo("UNKNOWN");
        assertThat(result.secondaryReasonCodes()).isEmpty();
    }

    @Test
    void 무효계약_지급_신호는_기존_MATCHED를_제거하고_불일치로_승격한다() {
        ReconciliationCandidate candidate = candidate(
                ReconciliationResultType.MATCHED, "MATCHED", List.of());
        ReconciliationClassificationContext context = new ReconciliationClassificationContext();
        context.setInvalidContractPayment(true);

        ReconciliationClassification result = classifier.classify(candidate, context);

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.INVALID_CONTRACT_PAYMENT);
        assertThat(result.primaryReasonCode()).isEqualTo("INVALID_CONTRACT_PAYMENT");
        assertThat(result.secondaryReasonCodes()).isEmpty();
    }

    @Test
    void 같은_입력은_반복해도_완전히_같은_결과를_만든다() {
        ReconciliationCandidate candidate = candidate(
                ReconciliationResultType.REVIEW_REQUIRED,
                "REVIEW_REQUIRED",
                List.of("AMOUNT_DIFFERENCE", "INSTALLMENT_MISMATCH"));

        ReconciliationClassification first = classifier.classify(
                candidate, new ReconciliationClassificationContext());
        ReconciliationClassification second = classifier.classify(
                candidate, new ReconciliationClassificationContext());

        assertThat(second).isEqualTo(first);
        assertThat(first.primaryReasonCode()).isEqualTo("INSTALLMENT_MISMATCH");
        assertThat(first.secondaryReasonCodes()).containsExactly("AMOUNT_DIFFERENCE", "REVIEW_REQUIRED");
    }

    private static ReconciliationCandidate candidate(
            ReconciliationResultType resultType,
            String primaryReason,
            List<String> secondaryReasons
    ) {
        ReconciliationCandidate candidate = mock(ReconciliationCandidate.class);
        given(candidate.resultType()).willReturn(resultType);
        given(candidate.primaryReasonCode()).willReturn(primaryReason);
        given(candidate.secondaryReasonCodes()).willReturn(secondaryReasons);
        return candidate;
    }
}
