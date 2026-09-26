package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.ProductResponse;
import com.susukkang.fgc.base.dto.ProductSearchCriteria;
import com.susukkang.fgc.base.repository.ProductRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final String SORT =
            "insurerProductCode,asc;offeringVersion,asc;channelCode,asc;productOfferingId,asc";

    private final ProductRepository productRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(
            ProductSearchCriteria criteria,
            int page,
            int size
    ) {
        validateCriteria(criteria);
        validatePaging(page, size);

        long offsetLong = (long) (page - 1) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            throw validationException("page");
        }
        Page<ProductResponse> result = productRepository
                .search(criteria.insurerId(), criteria.asOf(), PageRequest.of(page - 1, size))
                .map(ProductResponse::from);

        return PageResponse.of(result.getContent(), page, size, result.getTotalElements(), SORT);
    }

    private void validateCriteria(ProductSearchCriteria criteria) {
        if (criteria.insurerId() < 1) {
            throw validationException("insurerId");
        }
    }

    private void validatePaging(int page, int size) {
        if (page < MIN_PAGE) {
            throw validationException("page");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw validationException("size");
        }
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
