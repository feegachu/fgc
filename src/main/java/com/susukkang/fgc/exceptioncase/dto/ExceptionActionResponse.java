package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;
import com.susukkang.fgc.common.util.DateUtil;

import java.time.OffsetDateTime;

/** 오른쪽 처리 패널의 시간순 조치 이력 1건. */
public record ExceptionActionResponse(
        Long exceptionActionId,
        int actionSeq,
        ExceptionStatus fromStatus,
        ExceptionStatus toStatus,
        String actionType,
        String reason,
        String evidenceRef,
        Long actionBy,
        String actionByLoginId,
        OffsetDateTime actionAt
) {
    public static ExceptionActionResponse from(ExceptionActionRow row) {
        return new ExceptionActionResponse(
                row.exceptionActionId(), row.actionSeq(),
                row.fromStatus() == null ? null : ExceptionStatus.valueOf(row.fromStatus()),
                ExceptionStatus.valueOf(row.toStatus()), row.actionType(), row.reason(),
                row.evidenceRef(), row.actionBy(), row.actionByLoginId(),
                DateUtil.toSeoul(row.actionAt())
        );
    }
}
