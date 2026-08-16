package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalImbalanceSearchResponse;

/**
 * 검증 실행 범위의 차변·대변 불균형 분개 조회(GET /api/v1/journals/imbalances).
 * LEDG-W01 경고 배너와 VRUN-W02 확정 조건이 함께 쓴다(IF-API-37).
 */
public interface JournalImbalanceService {

    /**
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         (COMMON_004) validationRunId에 해당하는 검증 실행이 없을 때
     */
    JournalImbalanceSearchResponse findImbalances(Long validationRunId);
}
