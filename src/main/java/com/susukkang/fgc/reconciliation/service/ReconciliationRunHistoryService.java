package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunSearchCriteria;

import java.util.List;

/**
 * IF-API-39 대사 실행 이력 조회(RECO-W01 "④ 실행 이력"). 내부 Service 호출 전용
 */
public interface ReconciliationRunHistoryService {

    /** criteria로 대사 실행 이력을 검색한다. 결과가 없으면 빈 리스트(오류 아님). */
    List<ReconciliationRunHistoryResponse> findHistory(ReconciliationRunSearchCriteria criteria);
}
