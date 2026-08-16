package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionType;

/** 유형별 미처리(NEW+IN_REVIEW) 요약 카드 1건. */
public record ExceptionTypeSummaryResponse(ExceptionType type, long count) {
    public static ExceptionTypeSummaryResponse from(ExceptionTypeSummaryRow row) {
        return new ExceptionTypeSummaryResponse(ExceptionType.valueOf(row.exceptionType()), row.count());
    }
}
