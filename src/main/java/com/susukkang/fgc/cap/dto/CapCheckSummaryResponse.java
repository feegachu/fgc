package com.susukkang.fgc.cap.dto;

/** CAP-W01 요약 카드 4장 응답. */
public record CapCheckSummaryResponse(long normal, long warning, long violation, long reviewRequired) {
    public static CapCheckSummaryResponse from(CapCheckSummary summary) {
        return new CapCheckSummaryResponse(
                summary.normal(), summary.warning(), summary.violation(), summary.reviewRequired());
    }
}
