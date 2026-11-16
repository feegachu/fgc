package com.susukkang.fgc.reconciliation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultBasisResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultDetailRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultListItemResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationResultSearchResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationSummaryRow;
import com.susukkang.fgc.reconciliation.repository.ReconciliationResultRepository;
import com.susukkang.fgc.reconciliation.repository.ReconciliationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** FUN-049-02/03 대사 결과 목록·상세 조회. */
@Service
@RequiredArgsConstructor
public class ReconciliationResultQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ReconciliationResultSearchResponse search(
            long reconciliationRunId,
            String resultType,
            int page,
            int size,
            String sort
    ) {
        return searchResults(reconciliationRunId, resultType, null, page, size, sort);
    }

    @Transactional(readOnly = true)
    public ReconciliationResultSearchResponse search(long reconciliationRunId, String resultType, Long contractId,
                                                      int page, int size, String sort) {
        return searchResults(reconciliationRunId, resultType, contractId, page, size, sort);
    }

    private ReconciliationResultSearchResponse searchResults(long reconciliationRunId, String resultType, Long contractId,
                                                             int page, int size, String sort) {
        if (contractId != null && contractId <= 0) throw invalid("contractId");
        validatePage(page, size);
        ListSort listSort = ListSort.parse(sort, ReconciliationResultRepository.RESULT_SORT_KEYS,
                ReconciliationResultRepository.DEFAULT_RESULT_SORT);
        if (reconciliationRunRepository.findById(reconciliationRunId) == null) {
            throw notFound(reconciliationRunId);
        }
        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw invalid("page");
        }
        var rows = contractId == null
                ? reconciliationResultRepository.findResults(reconciliationRunId, resultType, listSort, (int) offsetLong, size)
                : reconciliationResultRepository.findResults(reconciliationRunId, resultType, contractId, listSort, (int) offsetLong, size);
        List<ReconciliationResultListItemResponse> content = rows
                .stream()
                .map(ReconciliationResultListItemResponse::from)
                .toList();
        long total = contractId == null ? reconciliationResultRepository.countResults(reconciliationRunId, resultType)
                : reconciliationResultRepository.countResults(reconciliationRunId, resultType, contractId);
        ReconciliationSummaryRow summary = reconciliationResultRepository.findSummary(reconciliationRunId);
        return new ReconciliationResultSearchResponse(
                ReconciliationSummaryResponse.from(summary),
                PageResponse.of(content, page, size, total, listSort.value()));
    }

    @Transactional(readOnly = true)
    public ReconciliationResultDetailResponse get(long reconciliationResultId) {
        return readDetail(reconciliationResultId);
    }

    private ReconciliationResultDetailResponse readDetail(long reconciliationResultId) {
        ReconciliationResultDetailRow row = reconciliationResultRepository.findDetail(reconciliationResultId);
        if (row == null) {
            throw notFound(reconciliationResultId);
        }
        return ReconciliationResultDetailResponse.from(
                row,
                reconciliationResultRepository.findMatches(reconciliationResultId),
                readSnapshot(row.getDetailSnapshotJson()));
    }

    @Transactional(readOnly = true)
    public ReconciliationResultBasisResponse basis(long resultId) {
        var detected = readDetail(resultId);
        var runId = reconciliationResultRepository.latestRevisionRun(resultId);
        var current = runId == null ? List.<ReconciliationResultDetailResponse>of()
                : reconciliationResultRepository.revisedResultIds(resultId, runId).stream().map(this::readDetail).toList();
        return new ReconciliationResultBasisResponse(detected, runId, current);
    }

    private JsonNode readSnapshot(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("대사 결과 상세 snapshot을 읽지 못했습니다.", exception);
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 1) {
            throw invalid("page");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size");
        }
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }

    private static FgcBusinessException notFound(long id) {
        return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", id));
    }
}
