package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReconciliationRunHistoryServiceImpl implements ReconciliationRunHistoryService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final Set<String> SUPPORTED_SORTS = Set.of("createdAt,asc", "createdAt,desc");

    private final ReconciliationRunHistoryMapper reconciliationRunHistoryMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReconciliationRunHistoryResponse> findHistory(
            ReconciliationRunSearchCriteria criteria, int page, int size, String sort) {
        if (page < MIN_PAGE) {
            throw invalid("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw invalid("size");
        }
        if (!SUPPORTED_SORTS.contains(sort)) {
            throw invalid("sort");
        }

        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw invalid("page");
        }
        int offset = (int) offsetLong;

        String direction = sort.endsWith(",asc") ? "asc" : "desc";

        List<ReconciliationRunHistoryResponse> content = reconciliationRunHistoryMapper
                .search(criteria.settlementMonth(), criteria.paymentStage(), direction, offset, size)
                .stream()
                .map(this::toResponse)
                .toList();
        long total = reconciliationRunHistoryMapper.count(criteria.settlementMonth(), criteria.paymentStage());

        return PageResponse.of(content, page, size, total, sort);
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }

    /**
     * ReconciliationRunHistoryRow 1행을 응답 1건으로 바꾼다
     */
    private ReconciliationRunHistoryResponse toResponse(ReconciliationRunHistoryRow row) {
        PaymentStage paymentStage = PaymentStage.valueOf(row.getPaymentStage());
        ValidationRunStatus status = ValidationRunStatus.valueOf(row.getStatus());

        // 일치율(%) = matched/target*100, 소수 첫째 자리까지 한 번만 반올림한다(중간에
        // 반올림을 한 번 더 하면 오차가 생길 수 있어 100을 먼저 곱하고 나눗셈에서 바로
        // 최종 자리수로 반올림한다). target이 0이면 분모가 없으므로 null로 둔다.
        BigDecimal matchRatePct;
        if (row.getTargetCount() == 0) {
            matchRatePct = null;
        } else {
            matchRatePct = BigDecimal.valueOf(row.getMatchedCount())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(row.getTargetCount()), 1, RoundingMode.HALF_UP);
        }

        return new ReconciliationRunHistoryResponse(
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
                matchRatePct);
    }
}
