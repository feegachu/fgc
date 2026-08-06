package com.susukkang.fgc.cap.dto;

import java.time.LocalDate;

/**
 * IF-API-30 검색조건. month는 화면에서 "정산월"로 부르지만 cap_check에는 그런 컬럼이 없어
 * as_of_date가 속한 달로 거른다(그 달 1일로 받는다 — SIR-008 정산월 관행과 동일).
 */
public record CapCheckSearchCriteria(
        LocalDate month,
        String paymentStage,
        String resultStatus,
        Long insurerId,
        String contractNo
) {
}
