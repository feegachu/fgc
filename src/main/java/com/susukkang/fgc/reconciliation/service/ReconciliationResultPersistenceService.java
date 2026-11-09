package com.susukkang.fgc.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.dto.ReconciliationCandidate;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassification;
import com.susukkang.fgc.reconciliation.dto.ReconciliationClassificationContext;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationMatchSource;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultInsertRow;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.repository.ReconciliationResultRepository;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 설명 : FUN-048-04 대사 후보·비교값·원천 추적정보 저장 서비스
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationResultPersistenceService {

    private final ReconciliationResultRepository reconciliationResultRepository;
    private final ObjectMapper objectMapper;
    private final ReconciliationReasonClassifier reasonClassifier;

    public long persist(
            ReconciliationExecutionRequest request,
            List<? extends ReconciliationCandidate> candidates
    ) {
        for (ReconciliationCandidate candidate : candidates) {
            persistCandidate(request, candidate);
        }
        return candidates.size();
    }

    private void persistCandidate(ReconciliationExecutionRequest request, ReconciliationCandidate candidate) {
        ReconciliationResultInsertRow row = toInsertRow(request, candidate);
        int inserted = reconciliationResultRepository.insertResult(row);
        Long resultId = row.getReconciliationResultId();
        if (resultId == null) {
            resultId = reconciliationResultRepository.findResultId(
                    request.reconciliationRunId(), candidate.matchGroupKey());
        }
        if (resultId == null) {
            throw new IllegalStateException("저장된 대사 결과를 조회하지 못했습니다.");
        }

        // 새 결과만 상세행을 씁니다. 충돌한 기존 결과는 감사 snapshot을 덮어쓰지 않습니다.
        if (inserted == 1) {
            int matchSeq = 1;
            for (ReconciliationMatchSource source : candidate.sourceMatches()) {
                reconciliationResultRepository.insertMatch(new ReconciliationMatchInsertRow(
                        resultId,
                        matchSeq++,
                        source.scheduleLineId(),
                        source.transactionAttributionId(),
                        source.matchedAmount(),
                        source.matchRole()
                ));
            }
        }
    }

    private ReconciliationResultInsertRow toInsertRow(
            ReconciliationExecutionRequest request,
            ReconciliationCandidate candidate
    ) {
        ReconciliationResultInsertRow row = new ReconciliationResultInsertRow();
        List<Long> journalHeaderIds = Stream.concat(
                        candidate.expectedJournalHeaderIds().stream(),
                        candidate.actualJournalHeaderIds().stream())
                .distinct()
                .sorted()
                .toList();
        ReconciliationClassificationContext context = reconciliationResultRepository.findClassificationContext(
                request.validationRunId(),
                candidate.contractId(),
                candidate.expectedAgentId(),
                candidate.actualAgentId(),
                journalHeaderIds,
                candidate.dueDate()
        );
        context.setMissingJournalEvidence(candidate.sourceMatches().stream()
                .anyMatch(source -> source.journalHeaderId() == null));
        ReconciliationClassification classification = reasonClassifier.classify(candidate, context);
        BigDecimal deduction = BigDecimal.ZERO;
        if (request.paymentStage() == PaymentStage.GA_TO_FC && classification.resultType() == ReconciliationResultType.INVALID_CONTRACT_PAYMENT) {
            deduction = reconciliationResultRepository.confirmedRecoveryAmount(candidate.sourceMatches().stream()
                    .map(ReconciliationMatchSource::transactionAttributionId).filter(Objects::nonNull).distinct().toList());
        }
        BigDecimal actual = candidate.actualTotalAmount().subtract(deduction).max(BigDecimal.ZERO);
        BigDecimal difference = deduction.signum() == 0 ? candidate.differenceAmount() : actual.subtract(candidate.expectedTotalAmount());
        if (deduction.signum() > 0 && difference.signum() == 0) {
            classification = new ReconciliationClassification(ReconciliationResultType.MATCHED, "RECOVERY_CONFIRMED", List.of());
        }
        row.setReconciliationRunId(request.reconciliationRunId());
        row.setMatchGroupKey(candidate.matchGroupKey());
        row.setContractId(candidate.contractId());
        row.setExpectedAgentId(candidate.expectedAgentId());
        row.setActualAgentId(candidate.actualAgentId());
        row.setActualSourceAgentCode(candidate.actualSourceAgentCode());
        row.setCommissionItemId(candidate.commissionItemId());
        row.setInstallmentNo(candidate.installmentNo());
        row.setResultType(classification.resultType().name());
        row.setExpectedTotalAmount(candidate.expectedTotalAmount());
        row.setActualTotalAmount(actual);
        row.setDifferenceAmount(difference);
        row.setPrimaryReasonCode(classification.primaryReasonCode());
        row.setSecondaryReasonCodes(classification.secondaryReasonCodes());
        row.setDetailSnapshotJson(writeSnapshot(request.paymentStage(), candidate, deduction));
        return row;
    }

    private String writeSnapshot(PaymentStage paymentStage, ReconciliationCandidate candidate, BigDecimal deduction) {
        try {
            return objectMapper.writeValueAsString(new DetailSnapshot(
                    paymentStage,
                    candidate.installmentNo(),
                    candidate.actualInstallmentNo(),
                    candidate.dueDate(),
                    candidate.dueMonth(),
                    candidate.expectedJournalHeaderIds(),
                    candidate.actualJournalHeaderIds(),
                    candidate.sourceMatches(),
                    candidate.actualTotalAmount(), deduction
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("대사 비교 snapshot을 직렬화하지 못했습니다.", exception);
        }
    }

    private record DetailSnapshot(
            PaymentStage paymentStage,
            Integer expectedInstallmentNo,
            Integer actualInstallmentNo,
            java.time.LocalDate dueDate,
            java.time.LocalDate dueMonth,
            List<Long> expectedJournalHeaderIds,
            List<Long> actualJournalHeaderIds,
            List<ReconciliationMatchSource> sources,
            BigDecimal originalActualAmount,
            BigDecimal recoveryDeductionAmount
    ) {
    }
}
