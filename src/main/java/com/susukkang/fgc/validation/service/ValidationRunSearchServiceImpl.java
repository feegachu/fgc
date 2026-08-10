package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunSearchServiceImpl implements ValidationRunSearchService {

    private final ValidationRunMapper validationRunMapper;
    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ValidationRunListRow> search(ValidationRunSearchCriteria criteria, int page, int size) {
        // 1) page/size 유효성 검증
        if(page < MIN_PAGE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }
        if(size < MIN_SIZE || size > MAX_SIZE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "size", Map.of("field", "size"), null);
        }

        // 2) offset 계산 (오버플로 방지 포함
        long offsetLong = (long) (page-1) * size;
        if(offsetLong > Integer.MAX_VALUE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }
        int offset = (int) offsetLong;

        // 3) 매퍼 호출 (목록 + 전체 건수)
        List<ValidationRunListRow> rows = validationRunMapper.search(
                criteria.month(), criteria.status(), offset, size
        );
        long total = validationRunMapper.count(criteria.month(), criteria.status());

        // 4) PageResponse로 조립
         return PageResponse.of(rows, page, size, total, "validationMonth,desc");
    }
}
