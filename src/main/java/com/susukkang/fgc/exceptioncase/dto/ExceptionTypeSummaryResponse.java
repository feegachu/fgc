package com.susukkang.fgc.exceptioncase.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.susukkang.fgc.common.code.ExceptionType;

/** 유형별 미처리(NEW+IN_REVIEW) 요약 카드 1건. */
public record ExceptionTypeSummaryResponse(ExceptionType type, long count) {
    /** 요약 카드 제목 — API 응답에도 한글 라벨을 함께 내려준다(SIR-008). */
    @JsonProperty("typeLabel")
    public String typeLabel() {
        return type.label();
    }

    public static ExceptionTypeSummaryResponse from(ExceptionTypeSummaryRow row) {
        return new ExceptionTypeSummaryResponse(ExceptionType.valueOf(row.exceptionType()), row.count());
    }
}
