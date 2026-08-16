package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.journal.domain.JournalType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 검증원장 목록 1건의 API 응답
 */
public record JournalItemResponse(
        Long journalHeaderId,
        String journalNo,
        LocalDate journalDate,
        JournalType journalType,
        String journalTypeLabel,
        String sourceEntityType,
        String sourceEntityId,
        Long contractId,
        String contractNo,
        JournalHeaderStatus status,
        String statusLabel,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        Long reversalOfId,
        String reversalOfJournalNo,
        String createdBy,
        String postedBy,
        OffsetDateTime postedAt,
        OffsetDateTime createdAt
) {
    public static JournalItemResponse from(JournalListRow row) {
        JournalType journalType = JournalType.valueOf(row.getJournalType());
        JournalHeaderStatus status = JournalHeaderStatus.valueOf(row.getStatus());
        return new JournalItemResponse(
                row.getJournalHeaderId(), row.getJournalNo(), row.getJournalDate(),
                journalType, journalType.label(),
                row.getSourceEntityType(), row.getSourceEntityId(),
                row.getContractId(), row.getContractNo(),
                status, status.label(),
                row.getDebitTotal(), row.getCreditTotal(),
                row.getReversalOfId(), row.getReversalOfJournalNo(),
                row.getCreatedBy(), row.getPostedBy(), row.getPostedAt(), row.getCreatedAt());
    }
}
