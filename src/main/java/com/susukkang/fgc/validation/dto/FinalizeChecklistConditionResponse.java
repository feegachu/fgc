package com.susukkang.fgc.validation.dto;

/** 월 통합검증 확정 조건 1건. count는 해당 조건을 위반한 행의 수다. */
public record FinalizeChecklistConditionResponse(
        int no,
        String label,
        boolean passed,
        long count,
        String linkUrl
) {
}
