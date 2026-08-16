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
import com.susukkang.fgc.reconciliation.mapper.ReconciliationResultMapper;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final ReconciliationResultMapper reconciliationResultMapper;
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
        int inserted = reconciliationResultMapper.insertResult(row);
        Long resultId = row.getReconciliationResultId();
        if (resultId == null) {
            resultId = reconciliationResultMapper.findResultId(
                    request.reconciliationRunId(), candidate.matchGroupKey());
        }
        if (resultId == null) {
            throw new IllegalStateException("저장된 대사 결과를 조회하지 못했습니다.");
        }

        // 새 결과만 상세행을 씁니다. 충돌한 기존 결과는 감사 snapshot을 덮어쓰지 않습니다.
        if (inserted == 1) {
            int matchSeq = 1;
            for (ReconciliationMatchSource source : candidate.sourceMatches()) {
                reconciliationResultMapper.insertMatch(new ReconciliationMatchInsertRow(
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
        ReconciliationClassificationContext context = reconciliationResultMapper.findClassificationContext(
                candidate.contractId(),
                candidate.expectedAgentId(),
                candidate.actualAgentId(),
                journalHeaderIds
        );
        context.setMissingJournalEvidence(candidate.sourceMatches().stream()
                .anyMatch(source -> source.journalHeaderId() == null));
        ReconciliationClassification classification = reasonClassifier.classify(candidate, context);
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
        row.setActualTotalAmount(candidate.actualTotalAmount());
        row.setDifferenceAmount(candidate.differenceAmount());
        row.setPrimaryReasonCode(classification.primaryReasonCode());
        row.setSecondaryReasonCodes(classification.secondaryReasonCodes());
        row.setDetailSnapshotJson(writeSnapshot(request.paymentStage(), candidate));
        return row;
    }

    private String writeSnapshot(PaymentStage paymentStage, ReconciliationCandidate candidate) {
        try {
            return objectMapper.writeValueAsString(new DetailSnapshot(
                    paymentStage,
                    candidate.installmentNo(),
                    candidate.actualInstallmentNo(),
                    candidate.dueDate(),
                    candidate.dueMonth(),
                    candidate.expectedJournalHeaderIds(),
                    candidate.actualJournalHeaderIds(),
                    candidate.sourceMatches()
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
            List<ReconciliationMatchSource> sources
    ) {
    }
}
