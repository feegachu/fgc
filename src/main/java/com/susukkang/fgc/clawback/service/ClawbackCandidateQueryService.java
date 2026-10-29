package com.susukkang.fgc.clawback.service;

import com.susukkang.fgc.clawback.dto.ClawbackCandidateDetailResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateResponse;
import com.susukkang.fgc.clawback.dto.ClawbackCandidateSearchCondition;
import com.susukkang.fgc.clawback.repository.ClawbackCandidateQueryRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** FUN-054 검색 검증과 읽기 트랜잭션. count와 목록도 동일한 DB snapshot에서 읽는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ClawbackCandidateQueryService {
    private final ClawbackCandidateQueryRepository repository;

    public PageResponse<ClawbackCandidateResponse> search(ClawbackCandidateSearchCondition condition, int page, int size) {
        if (page < 1 || (long) (page - 1) * size > Integer.MAX_VALUE) throw invalid("page");
        if (size < 1 || size > 100) throw invalid("size");
        if (condition.getDateFrom() != null && condition.getDateTo() != null
                && condition.getDateFrom().isAfter(condition.getDateTo())) throw invalid("dateTo");
        if (condition.getContractNo() != null && condition.getContractNo().trim().length() > 80) throw invalid("contractNo");
        positive(condition.getInsurerId(), "insurerId");
        positive(condition.getProductOfferingId(), "productOfferingId");
        positive(condition.getAgentId(), "agentId");
        ListSort sort = ListSort.parse(condition.getSort(), ClawbackCandidateQueryRepository.SORTABLE.keySet(),
                ClawbackCandidateQueryRepository.DEFAULT_SORT);
        var result = repository.search(condition, sort, page, size);
        return PageResponse.of(result.items().stream().map(ClawbackCandidateDetailResponse::candidate).toList(),
                page, size, result.total(), sort.value());
    }

    public ClawbackCandidateDetailResponse detail(long candidateId) {
        if (candidateId < 1) throw invalid("candidateId");
        return repository.findById(candidateId).orElseThrow(() ->
                new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", candidateId)));
    }

    private static void positive(Long value, String field) {
        if (value != null && value < 1) throw invalid(field);
    }

    private static FgcBusinessException invalid(String field) {
        return new FgcBusinessException(FgcErrorCode.COMMON_002, field, Map.of("field", field), null);
    }
}
