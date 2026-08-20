package com.susukkang.fgc.journal.dto;

import lombok.Builder;

import java.time.LocalDate;

/**
 * 설명 : IF-API-36A가 기존 exception_case에 저장할 원장 정정 업무건
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Builder
public record JournalCorrectionExceptionInsertCommand(
        String exceptionKey,
        Long validationRunId,
        LocalDate validationMonth,
        Long contractId,
        Long policyVersionId,
        Long journalHeaderId,
        String title,
        String description,
        String reason,
        String evidenceRef,
        Long requestedBy
) {
}
