package com.susukkang.fgc.exceptioncase.dto;

/** 유형·심각도별 예외 건수 DB 투영. IF-API-43 요약과 IF-API-69 집계가 같은 행을 쓴다. */
public record ExceptionCountRow(String exceptionType, String severity, long count) {
}
