package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.journal.domain.JournalType;

import java.math.BigDecimal;

/**
 * GET /api/v1/journals/imbalances 응답의 목록 1건
 * LedgerImbalanceRow(내부 조회 결과)를 그대로 밖에 노출하지 않고 별도 Response로 감싼다.
 */
public record JournalImbalanceItemResponse(
        Long journalHeaderId,
        String journalNo,
        JournalType journalType,
        String journalTypeLabel,
        JournalHeaderStatus status,
        String statusLabel,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        BigDecimal differenceAmount
) {
    public static JournalImbalanceItemResponse from(LedgerImbalanceRow row) {
        JournalType journalType = JournalType.valueOf(row.getJournalType());
        JournalHeaderStatus status = JournalHeaderStatus.valueOf(row.getStatus());
        return new JournalImbalanceItemResponse(
                row.getJournalHeaderId(), row.getJournalNo(),
                journalType, journalType.label(),
                status, status.label(),
                row.getDebitTotal(), row.getCreditTotal(), row.getDifferenceAmount());
    }
}
