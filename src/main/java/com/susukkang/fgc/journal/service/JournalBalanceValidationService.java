package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalBalanceSummary;

/**
 * 분개 헤더 하나의 차변·대변 균형 검증
 * DRAFT→POSTED 전이를 시도하는 쪽이 실제 UPDATE 전에 이 서비스로 먼저 확인해야함
 */
public interface JournalBalanceValidationService {

    /** journalHeaderId 하나의 차변·대변 합계를 계산한다. */
    JournalBalanceSummary summarize(Long journalHeaderId);

    /**
     * summarize() 결과가 균형이 아니면 FgcBusinessException(FgcErrorCode.LEDG_001)을
     * 던진다. 균형이면 아무 것도 하지 않고 정상 반환한다.
     */
    void assertBalanced(Long journalHeaderId);
}
