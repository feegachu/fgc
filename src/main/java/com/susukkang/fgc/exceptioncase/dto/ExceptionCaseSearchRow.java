package com.susukkang.fgc.exceptioncase.dto;

import java.time.OffsetDateTime;

/** 예외 목록·처리 패널 조회를 위한 DB 투영 1행. */
public record ExceptionCaseSearchRow(
        Long exceptionCaseId,
        String exceptionKey,
        String exceptionType,
        String severity,
        String status,
        String title,
        String description,
        String contractNo,
        String agentName,
        Long assignedTo,
        String assigneeLoginId,
        String sourceEntityType,
        String sourceEntityId,
        OffsetDateTime createdAt
) {
}
