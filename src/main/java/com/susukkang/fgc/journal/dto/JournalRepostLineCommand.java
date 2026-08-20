package com.susukkang.fgc.journal.dto;

import java.math.BigDecimal;

/**
 * 설명 : 원분개 한 줄을 기준으로 입력한 재기표 라인 변경값
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalRepostLineCommand(
        Integer originalLineNo,
        String accountCode,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String lineDescription
) {
}
