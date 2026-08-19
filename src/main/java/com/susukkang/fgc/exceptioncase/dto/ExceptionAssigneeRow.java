package com.susukkang.fgc.exceptioncase.dto;

/** 담당자 필터 선택지 — 예외를 배정받은 적 있는 사용자만 나열한다. */
public record ExceptionAssigneeRow(Long userId, String loginId) {
}
