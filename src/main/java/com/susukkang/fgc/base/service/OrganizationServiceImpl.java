package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.OrganizationResponse;
import com.susukkang.fgc.base.dto.OrganizationSearchCriteria;
import com.susukkang.fgc.base.repository.OrganizationRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.JpaListSort;
import com.susukkang.fgc.common.web.ListSort;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "organizationCode,asc";
    /** 화면 정렬 key(기준정보 조직 탭) → JPQL 표현식. 유형·상태는 순서 기준이 없어 제외한다. */
    private static final Map<String, String> SORTABLE = Map.of(
            "organizationCode", "o.organizationCode",
            "organizationName", "o.organizationName",
            "parentName", "parent.organizationName",
            "effectiveFrom", "o.effectiveFrom",
            "effectiveTo", "o.effectiveTo");
    public static final Set<String> SORT_KEYS = Set.copyOf(SORTABLE.keySet());

    private final OrganizationRepository organizationRepository;

    /** 요청 정렬을 검증한다. 허용 key 가 아니면 COMMON_002. 비어 있으면 null — 기존 조직코드 오름차순. */
    public static ListSort sortOf(String raw) {
        return ListSort.parse(raw, SORT_KEYS, null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrganizationResponse> search(
            OrganizationSearchCriteria criteria,
            int page,
            int size
    ) {
        validatePaging(page, size);

        String normalizedKeyword = normalizeKeyword(criteria.keyword());

        ListSort sort = sortOf(criteria.sort());
        Pageable pageable = JpaListSort.pageable(page, size, sort, SORTABLE, Sort.by(Sort.Direction.ASC, "organizationCode"));

        Page<OrganizationResponse> result = (criteria.includeHistorical()
                ? organizationRepository.search(normalizedKeyword, criteria.asOf(), true, pageable)
                : organizationRepository.search(normalizedKeyword, criteria.asOf(), pageable))
                .map(OrganizationResponse::from);

        return PageResponse.of(
                result.getContent(),
                page,
                size,
                result.getTotalElements(),
                sort == null ? SORT : sort.value()
        );
    }

    private void validatePaging(int page, int size) {
        if (page < MIN_PAGE) {
            throw validationException("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException("size");
        }
        long offset = (long)(page - 1) * size;
        if (offset > Integer.MAX_VALUE)
            throw validationException("page");
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
