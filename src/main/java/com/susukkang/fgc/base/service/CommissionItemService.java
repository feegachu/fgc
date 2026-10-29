package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.repository.CommissionItemRepository;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.PageResponse;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CommissionItemService {

    private final CommissionItemRepository commissionItemRepository;

    @Transactional(readOnly = true)
    public PageResponse<CommissionItemResponse> search(
            LocalDate asOf, String keyword, String cashflowType, Long selectedId, int page, int size) {
        String flow = cashflowType == null ? "" : cashflowType;
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE
                || (selectedId != null && selectedId < 1)
                || (!flow.isEmpty() && !flow.equals("PAYMENT") && !flow.equals("DEDUCTION"))) {
            throw new FgcBusinessException(FgcErrorCode.COMMON_002);
        }
        var result = commissionItemRepository.searchItems(asOf, keyword == null ? "" : keyword.trim(), flow,
                selectedId, PageRequest.of(page - 1, size));
        return PageResponse.of(result.getContent(), page, size,
                result.getTotalElements(), "itemCode,asc;commissionItemId,asc");
    }

    @Transactional(readOnly = true)
    public List<CommissionItemResponse> findEffectiveItems(LocalDate asOf) {
        Objects.requireNonNull(asOf, "기준일자(asOf)는 필수입니다.");
        return commissionItemRepository.findEffectiveItems(asOf);
    }
}
