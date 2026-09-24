package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.InsurerResponse;
import com.susukkang.fgc.base.entity.Insurer;
import com.susukkang.fgc.base.repository.InsurerRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Pageable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InsurerServiceImpl implements InsurerService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT = "insurerCode,asc";

    private final InsurerRepository insurerRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InsurerResponse> search(String keyword, int page, int size) {
        validatePaging(page, size);
        String normalizedKeyword = normalizeKeyword(keyword);

        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Direction.ASC, "insurerCode")
        );
        Page<Insurer> insurerPage = insurerRepository.search(normalizedKeyword,pageable);

        List<InsurerResponse> content = insurerPage.getContent()
                .stream()
                .map(InsurerResponse::from)
                .toList();

        return PageResponse.of(
                content,
                page,
                size,
                insurerPage.getTotalElements(),
                SORT
        );
    }

    private void validatePaging(int page, int size) {
        if (page < MIN_PAGE) {
            throw validationException("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException("size");
        }
        long offset = (long) (page - 1) * size;
        if ( offset > Integer.MAX_VALUE)
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
