package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.AgentResponse;
import com.susukkang.fgc.base.dto.AgentSearchCriteria;
import com.susukkang.fgc.base.repository.AgentRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "agentCode,asc";

    private final AgentRepository agentRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AgentResponse> search(AgentSearchCriteria criteria, int page, int size) {
        validateCriteria(criteria);
        validatePaging(page, size);

        String normalizedKeyword = normalizeKeyword(criteria.keyword());
        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Direction.ASC, "agentCode")
        );

        Page<AgentResponse> result = agentRepository
                .search(criteria.organizationId(), normalizedKeyword, criteria.asOf(), pageable)
                .map(AgentResponse::from);

        return PageResponse.of(result.getContent(), page, size, result.getTotalElements(), SORT);
    }

    private void validateCriteria(AgentSearchCriteria criteria) {
        if (criteria.organizationId() != null && criteria.organizationId() < 1) {
            throw validationException("organizationId");
        }
    }

    private void validatePaging(int page, int size) {
        if (page < MIN_PAGE) {
            throw validationException("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException("size");
        }
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw validationException("page");
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    private FgcBusinessException validationException(String field) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                field,
                Map.of("field", field),
                null
        );
    }
}
