package com.susukkang.fgc.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultListItemResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultSearchResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryRow;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationResultMapper;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** FUN-049-02/03 대사 결과 목록·상세 조회. */
@Service
@RequiredArgsConstructor
public class ReconciliationResultQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SUPPORTED_SORTS = Set.of("createdAt,desc", "createdAt,asc");

    private final ReconciliationRunMapper reconciliationRunMapper;
    private final ReconciliationResultMapper reconciliationResultMapper;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ReconciliationResultSearchResponse search(
            long reconciliationRunId,
            String resultType,
            int page,
            int size,
            String sort
    ) {
        validatePage(page, size, sort);
        if (reconciliationRunMapper.findById(reconciliationRunId) == null) {
            throw notFound(reconciliationRunId);
        }
        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw invalid("page");
        }
        String direction = sort.endsWith(",asc") ? "asc" : "desc";
        List<ReconciliationResultListItemResponse> content = reconciliationResultMapper
                .findResults(reconciliationRunId, resultType, direction, (int) offsetLong, size)
                .stream()
                .map(ReconciliationResultListItemResponse::from)
                .toList();
        long total = reconciliationResultMapper.countResults(reconciliationRunId, resultType);
        ReconciliationSummaryRow summary = reconciliationResultMapper.findSummary(reconciliationRunId);
        return new ReconciliationResultSearchResponse(
                ReconciliationSummaryResponse.from(summary),
                PageResponse.of(content, page, size, total, sort));
    }

    @Transactional(readOnly = true)
    public ReconciliationResultDetailResponse get(long reconciliationResultId) {
        ReconciliationResultDetailRow row = reconciliationResultMapper.findDetail(reconciliationResultId);
        if (row == null) {
            throw notFound(reconciliationResultId);
        }
        return ReconciliationResultDetailResponse.from(
                row,
                reconciliationResultMapper.findMatches(reconciliationResultId),
                readSnapshot(row.getDetailSnapshotJson()));
    }

    private JsonNode readSnapshot(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("대사 결과 상세 snapshot을 읽지 못했습니다.", exception);
        }
    }

    private static void validatePage(int page, int size, String sort) {
        if (page < 1) {
            throw invalid("page");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size");
        }
        if (!SUPPORTED_SORTS.contains(sort)) {
            throw invalid("sort");
        }
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }

    private static FgcBusinessException notFound(long id) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", id));
    }
}
