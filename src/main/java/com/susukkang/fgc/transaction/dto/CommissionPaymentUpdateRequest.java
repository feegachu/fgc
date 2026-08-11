package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 수정 요청
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
// 2026-08-11 yslee - IF-API-23 수정 요청을 등록 요청과 같은 계약으로 통일
// 기존 코드: 수정 요청에 원천 업무키가 없고 항목 코드·귀속월 중심의 내부 전용 필드를 사용
// 문제: DRAFT 원장의 자연키와 실제 귀속일을 수정 API로 온전히 보존하거나 변경할 수 없음
// 개선: 원천유형·업무키·항목 ID·정산월·현금흐름과 실제 귀속일 목록을 등록 API와 동일하게 입력
public record CommissionPaymentUpdateRequest(
        @NotBlank @Pattern(regexp = "GA_MANUAL_PAYMENT") String sourceType,
        @NotBlank @Size(max = 160) String sourceBusinessKey,
        Long contractId,
        @NotNull Long agentId,
        @NotNull @Positive Long commissionItemId,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate settlementMonth,
        @NotBlank @Pattern(regexp = "PAYMENT|DEDUCTION") String cashflowType,
        @NotNull LocalDate scheduledPaymentDate,
        @NotNull PaymentStage paymentStage,
        Long allocationPolicyVersion,
        @NotNull List<@NotNull @Valid CommissionPaymentAttributionRequest> attributions,
        @Size(max = 1000) String note
) {
}
