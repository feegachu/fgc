package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.dto.ValidationRunSearchCriteria;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ValidationRunSearchServiceImpl implements ValidationRunSearchService {

    private final ValidationRunRepository validationRunRepository;
    private static final int MIN_PAGE = 1;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final List<ValidationRunStatus> ACTIVE_STATUSES =
            List.of(ValidationRunStatus.CREATED, ValidationRunStatus.RUNNING);

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

        // 2) offset 오버플로 방지 — Pageable 자체는 long으로 안전하게 계산하지만, 과거
        // offset 계약(COMMON_002)을 그대로 유지하기 위해 동일한 경계에서 동일하게 거절한다.
        long offsetLong = (long) (page-1) * size;
        if(offsetLong > Integer.MAX_VALUE){
            throw new FgcBusinessException(FgcErrorCode.COMMON_002, "page", Map.of("field", "page"), null);
        }

        // 3) status는 컨트롤러가 이미 ValidationRunStatus.valueOf로 검증한 뒤 넘긴다
        ValidationRunStatus status = criteria.status() != null ? ValidationRunStatus.valueOf(criteria.status()) : null;

        // 4) Repository 호출 (목록 + 전체 건수를 Page 하나로)
        Page<ValidationRunListRow> resultPage = validationRunRepository.search(
                criteria.month(), status, PageRequest.of(page - 1, size));

        // 5) PageResponse로 조립
        return PageResponse.of(resultPage.getContent(), page, size, resultPage.getTotalElements(), "validationMonth,desc");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveMonthlyRun(LocalDate validationMonth) {
        return validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(
                validationMonth, "MONTHLY", ACTIVE_STATUSES);
    }
}
