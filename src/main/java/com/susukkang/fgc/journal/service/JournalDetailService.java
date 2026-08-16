package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalDetailResponse;

/**
 * 검증원장 상세 조회(GET /api/v1/journals/{journalHeaderId}).
 * LEDG-W01 상세 패널이 쓰는 헤더 + 라인 + 차대 합계·차액·균형 여부를 함께 돌려준다.
 */
public interface JournalDetailService {

    /**
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException
     *         (COMMON_004) journalHeaderId가 존재하지 않을 때
     */
    JournalDetailResponse findByJournalHeaderId(Long journalHeaderId);
}
