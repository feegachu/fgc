package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 등록 요청
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
// 2026-08-11 yslee - IF-API-22 저장 요청 계약을 인터페이스 정의서와 일치
// 기존 코드: 항목 코드를 받고 원천유형·현금흐름 구분·실제 귀속일을 서버가 임의 추론
// 문제: 화면 요청과 DB 저장값의 대응을 재현할 수 없고 월 단위 값만으로 규제 산입 기간을 판정
// 개선: 원천유형·항목 ID·정산월·현금흐름을 명시 입력하고 귀속 목록의 null 요소를 검증 단계에서 차단
public record CommissionPaymentCreateRequest(
        @NotBlank @Pattern(regexp = "GA_MANUAL_PAYMENT") String sourceType,
        @NotBlank @Size(max = 160) String sourceBusinessKey,
        @NotNull @Positive Integer paymentSequence,
        Long contractId,
        @NotNull Long agentId,
        @NotNull @Positive Long commissionItemId,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull LocalDate settlementMonth,
        @NotBlank @Pattern(regexp = "PAYMENT|DEDUCTION") String cashflowType,
        @NotNull LocalDate scheduledPaymentDate,
        @NotNull PaymentStage paymentStage,
        Long allocationPolicyVersion,
        @NotEmpty List<@NotNull @Valid CommissionPaymentAttributionRequest> attributions,
        @Size(max = 1000) String note
) {
}
