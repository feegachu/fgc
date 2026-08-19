package com.susukkang.fgc.exceptioncase.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** SRC-032 실행별 예외 검출 이력 DB 투영. */
public record ExceptionOccurrenceRow(
        Long exceptionOccurrenceId,
        Long exceptionCaseId,
        Long validationRunId,
        Integer runNo,
        LocalDate validationMonth,
        String exceptionType,
        String reasonCode,
        String sourceEntityType,
        String sourceEntityId,
        String evidenceJson,
        boolean newCase,
        boolean reopened,
        OffsetDateTime detectedAt
) {
}
