package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;

import java.time.OffsetDateTime;

/**
 * 설명 : IF-API-44A 원장 정정과 예외 종결의 통합 응답
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionActionResponse(
        int actionSeq,
        ExceptionStatus fromStatus,
        ExceptionStatus toStatus,
        String actionType,
        String reason,
        String evidenceRef,
        Long actionBy,
        String actionByLoginId,
        OffsetDateTime actionAt,
        Long originalJournalHeaderId,
        Long reversalJournalHeaderId,
        Long repostedJournalHeaderId,
        String correctionGroupKey
) {
    public static JournalCorrectionActionResponse from(
            ExceptionActionResponse action,
            JournalCorrectionResult correction
    ) {
        return new JournalCorrectionActionResponse(
                action.actionSeq(), action.fromStatus(), action.toStatus(), action.actionType(),
                action.reason(), action.evidenceRef(), action.actionBy(),
                action.actionByLoginId(), action.actionAt(), correction.reversalOfId(),
                correction.journalHeaderId(), correction.repostedJournalHeaderId(),
                correction.correctionGroupKey());
    }
}
