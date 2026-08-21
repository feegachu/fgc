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
        String sourceEntityTypeLabel,
        String sourceEntityId,
        Long contractId,
        String contractNo,
        JournalHeaderStatus status,
        String statusLabel,
        String statusTone,
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
                row.getSourceEntityType(), sourceEntityTypeLabel(row.getSourceEntityType()), row.getSourceEntityId(),
                row.getContractId(), row.getContractNo(),
                status, status.label(), status.tone(),
                row.getDebitTotal(), row.getCreditTotal(),
                row.getReversalOfId(), row.getReversalOfJournalNo(),
                row.getCreatedBy(), row.getPostedBy(), row.getPostedAt(), row.getCreatedAt());
    }

    /** journal_header.source_entity_type 값은 enum이 아니라 자유 문자열이라 여기서 한글 라벨을 붙인다. */
    private static String sourceEntityTypeLabel(String sourceEntityType) {
        if (sourceEntityType == null) {
            return null;
        }
        return switch (sourceEntityType) {
            case "SCHEDULE_LINE" -> "스케줄행";
            case "COMMISSION_TRANSACTION" -> "지급거래";
            case "JOURNAL_HEADER" -> "원분개";
            default -> sourceEntityType;
        };
    }
}
