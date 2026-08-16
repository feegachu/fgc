package com.susukkang.fgc.exceptioncase.dto;

import java.time.OffsetDateTime;

/** exception_action과 처리 사용자 login_id를 함께 조회한 DB 투영 1행. */
public record ExceptionActionRow(
        Long exceptionActionId,
        Long exceptionCaseId,
        int actionSeq,
        String fromStatus,
        String toStatus,
        String actionType,
        String reason,
        String evidenceRef,
        Long actionBy,
        String actionByLoginId,
        OffsetDateTime actionAt
) {
}
