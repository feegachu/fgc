package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationRow;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.mapper.OrganizationMapper;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "organizationCode,asc";

    private final OrganizationMapper organizationMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> search(
            OrganizationSearchCriteria criteria,
            int page,
            int size
    ) {
        validatePaging(page, size);

        String keyword = normalizeKeyword(criteria.keyword());
        OrganizationSearchCriteria normalizedCriteria =
                new OrganizationSearchCriteria(keyword, criteria.asOf());

        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw validationException("page");
        }
        int offset = (int) offsetLong;

        List<OrganizationResponse> content = organizationMapper
                .selectOrganizations(normalizedCriteria, offset, size)
                .stream()
                .map(OrganizationResponse::from)
                .toList();
        long totalElements = organizationMapper.countOrganizations(normalizedCriteria);

        return PageResponse.of(content, page, size, totalElements, SORT);
    }

    private void validatePaging(int page, int size) {
        if (page < MIN_PAGE) {
            throw validationException("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException("size");
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
