package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.journal.domain.JournalType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/v1/journals/{id} 응답 — LEDG-W01 상세 패널(④). 헤더 정보 + 라인 목록 +
 * 차변/대변 합계·차액·균형 여부를 함께 담는다.
 *
 * 원분개↔역분개 이력은 reversalOfId(이 분개가 역분개일 때 원분개)와
 * reversedByJournalHeaderId(이 분개가 나중에 역분개당했을 때 그 후속 분개) 양방향으로
 * 노출한다 — 원분개에서 조회해도, 역분개에서 조회해도 상대편을 찾을 수 있어야 한다.
 */
public record JournalDetailResponse(
        Long journalHeaderId,
        String journalNo,
        LocalDate journalDate,
        JournalType journalType,
        String journalTypeLabel,
        String sourceEntityType,
        String sourceEntityId,
        Integer revisionNo,
        Long contractId,
        String contractNo,
        Long validationRunId,
        Long policyVersionId,
        String correctionGroupKey,
        JournalHeaderStatus status,
        String statusLabel,
        String description,
        String createdBy,
        OffsetDateTime createdAt,
        String postedBy,
        OffsetDateTime postedAt,
        Long reversalOfId,
        String reversalOfJournalNo,
        Long reversedByJournalHeaderId,
        String reversedByJournalNo,
        Long repostedJournalHeaderId,
        String repostedJournalNo,
        BigDecimal debitTotal,
        BigDecimal creditTotal,
        BigDecimal differenceAmount,
        boolean balanced,
        List<JournalDetailLineResponse> lines
) {
}
