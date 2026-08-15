package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/** exception_action append-only INSERT에 사용하는 명령 DTO. */
@Getter
@Builder
public class ExceptionActionInsertCommand {
    private final Long exceptionCaseId;
    private final int actionSeq;
    private final ExceptionStatus fromStatus;
    private final ExceptionStatus toStatus;
    private final ExceptionActionType actionType;
    private final String reason;
    private final String evidenceRef;
    private final Long actionBy;
    private final OffsetDateTime actionAt;
}
