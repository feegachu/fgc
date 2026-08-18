package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.util.DateUtil;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 관리자에게 보여 줄 월 통합검증 실행별 검출 증거. */
public record ExceptionOccurrenceResponse(
        Long exceptionOccurrenceId,
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
    public static ExceptionOccurrenceResponse from(ExceptionOccurrenceRow row) {
        return new ExceptionOccurrenceResponse(
                row.exceptionOccurrenceId(), row.validationRunId(), row.runNo(),
                row.validationMonth(), row.exceptionType(), row.reasonCode(),
                row.sourceEntityType(), row.sourceEntityId(), row.evidenceJson(),
                row.newCase(), row.reopened(), DateUtil.toSeoul(row.detectedAt()));
    }

    public String detectionLabel() {
        if (reopened) return "재발·재개";
        return newCase ? "신규" : "재검출";
    }
}
