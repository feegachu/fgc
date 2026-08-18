package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;

/**
 * IF-API-39 대사 실행 이력 조회(RECO-W01 "④ 실행 이력").
 */
public interface ReconciliationRunHistoryService {

    /**
     * criteria로 대사 실행 이력을 페이징 검색한다. 결과가 없으면 빈 페이지(오류 아님).
     * sort는 ReconciliationResultQueryService와 같은 관례로 "createdAt,asc"/"createdAt,desc"만
     * 지원한다(그 외 값은 COMMON_002).
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         (COMMON_002) page&lt;1이거나 size가 1~100 범위를 벗어나거나 sort가 지원 목록에 없을 때
     */
    PageResponse<ReconciliationRunHistoryResponse> findHistory(
            ReconciliationRunSearchCriteria criteria, int page, int size, String sort);
}
