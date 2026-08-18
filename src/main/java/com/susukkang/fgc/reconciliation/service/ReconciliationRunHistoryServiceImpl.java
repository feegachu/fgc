package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReconciliationRunHistoryServiceImpl implements ReconciliationRunHistoryService {

    private final ReconciliationRunHistoryMapper reconciliationRunHistoryMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ReconciliationRunHistoryResponse> findHistory(ReconciliationRunSearchCriteria criteria) {
        // 1. 목록 조회
        List<ReconciliationRunHistoryRow> rows =
                reconciliationRunHistoryMapper.search(criteria.settlementMonth(), criteria.paymentStage());

        List<ReconciliationRunHistoryResponse> result = new ArrayList<>();
        for (ReconciliationRunHistoryRow row : rows) {
            PaymentStage paymentStage = PaymentStage.valueOf(row.getPaymentStage());
            ValidationRunStatus status = ValidationRunStatus.valueOf(row.getStatus());

            // 일치율 계산
            BigDecimal matchRatePct;
            if(row.getTargetCount()==0){
                matchRatePct = null;
            } else {
                matchRatePct = BigDecimal.valueOf(row.getMatchedCount())
                        .divide(BigDecimal.valueOf(row.getTargetCount()), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(1, RoundingMode.HALF_UP);
            }

            result.add(new ReconciliationRunHistoryResponse(
                    row.getReconciliationRunId(), row.getValidationRunId(),
                    row.getSettlementMonth(), row.getPaymentStage(), paymentStage.label(),
                    row.getInsurerId(), row.getInsurerName(),
                    row.getStatus(), status.label(),
                    row.getTolerancePolicyVersionId(),
                    row.getCreatedBy(), row.getCreatedAt(),
                    row.getStartedAt(), row.getCompletedAt(),
                    row.getFinalizedAt(), row.getFinalizedBy(),
                    row.getTargetCount(), row.getMatchedCount(), row.getExceptionCount(),
                    row.getExpectedTotal(), row.getActualTotal(), row.getDifferenceTotal(),
                    matchRatePct
            ));
        }
        return result;
    }
}
