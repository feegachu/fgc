package com.susukkang.fgc.exceptioncase.dto;

import java.time.OffsetDateTime;
import java.time.LocalDate;

/** 예외 목록·처리 패널 조회를 위한 DB 투영 1행. */
public record ExceptionCaseSearchRow(
        Long exceptionCaseId,
        String exceptionKey,
        String exceptionType,
        String reasonCode,
        String severity,
        String status,
        String title,
        String description,
        Long contractId,
        String contractNo,
        String agentName,
        Long assignedTo,
        String assigneeLoginId,
        String sourceEntityType,
        String sourceEntityId,
        String reconciliationResultType,
        LocalDate validationMonth,
        Long firstDetectedRunId,
        Long lastDetectedRunId,
        OffsetDateTime firstDetectedAt,
        OffsetDateTime lastDetectedAt,
        int detectionCount,
        OffsetDateTime createdAt
) {
}
