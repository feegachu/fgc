package com.susukkang.fgc.exceptioncase.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.susukkang.fgc.common.code.ExceptionSeverity;

/** IF-API-69 심각도별 건수 1건. */
public record ExceptionSeverityCountResponse(ExceptionSeverity severity, long count) {
    /** 코드값에 한글 라벨을 함께 내려준다(SIR-008). */
    @JsonProperty("severityLabel")
    public String severityLabel() {
        return severity.label();
    }
}
