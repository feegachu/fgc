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

            // 일치율 계산 — 반올림은 최종 결과 한 번만 한다(코드리뷰 반영). 먼저 소수
            // 넷째 자리로 나눠서 반올림한 다음 100을 곱하고 다시 소수 첫째 자리로
            // 반올림하면 두 번 반올림하는 셈이라 오차가 생긴다(예: matched=12449,
            // target=100000 → 정확한 값은 12.449%라 첫째 자리 반올림 시 12.4가 맞는데,
            // 넷째 자리에서 먼저 반올림하면 0.1245*100=12.45가 되고 그걸 다시 반올림해
            // 12.5로 틀어진다). 그래서 100을 먼저 곱한 뒤 나눗셈에서 바로 소수 첫째
            // 자리까지 한 번에 반올림한다.
            BigDecimal matchRatePct;
            if(row.getTargetCount()==0){
                matchRatePct = null;
            } else {
                matchRatePct = BigDecimal.valueOf(row.getMatchedCount())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(row.getTargetCount()), 1, RoundingMode.HALF_UP);
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
