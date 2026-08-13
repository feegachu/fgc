package com.susukkang.fgc.journal.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * GET /api/v1/journals/imbalances 응답 1건
 * LedgerImbalanceRow(내부 조회 결과)를 그대로 밖에 노출하지 않고 별도 Response로 감쌈
 */
@Getter
@Builder
public class JournalImbalanceResponse {
    private final Long journalHeaderId;
    private final String journalNo;
    private final String status;
    private final BigDecimal debitTotal;
    private final BigDecimal creditTotal;
    private final BigDecimal differenceAmount;

    public static JournalImbalanceResponse from(LedgerImbalanceRow row) {
        return JournalImbalanceResponse.builder()
                .journalHeaderId(row.getJournalHeaderId())
                .journalNo(row.getJournalNo())
                .status(row.getStatus())
                .debitTotal(row.getDebitTotal())
                .creditTotal(row.getCreditTotal())
                .differenceAmount(row.getDifferenceAmount())
                .build();
    }
}
