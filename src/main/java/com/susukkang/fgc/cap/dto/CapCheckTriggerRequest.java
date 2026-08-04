package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 초년도 1,200% 한도 계산 트리거 요청.
 * asOfDate 를 생략하면 오늘 날짜로 계산한다 — 이 엔진의 계산식은 계약 체결일만 기준으로 삼으므로
 * asOfDate 는 결과에 영향을 주지 않고 cap_check.as_of_date(감사용 "언제 평가했는지")에만 쓰인다.
 */
public record CapCheckTriggerRequest(
        @NotNull Long contractId,
        @NotNull PaymentStage paymentStage,
        LocalDate asOfDate
) {
}
