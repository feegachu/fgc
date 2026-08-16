package com.susukkang.fgc.exceptioncase.dto;

/** 유형별 미처리 예외 집계 DB 투영. */
public record ExceptionTypeSummaryRow(String exceptionType, long count) {
}
