package com.susukkang.fgc.transaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 응답
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
// 2026-08-11 yslee - 저장된 지급 건 본문 증빙을 응답에 노출
// 기존 코드: 귀속행 evidenceRef만 반환하고 지급 건 evidence_ref는 응답에서 누락
// 문제: 사용자가 저장한 원천 증빙을 등록·수정 결과로 확인할 수 없음
// 개선: 부모 지급 건 evidenceRef를 귀속행 목록과 구분해 반환
public record CommissionPaymentResponse(
        @JsonProperty("commissionTransactionId") Long paymentId,
        String sourceType,
        String sourceBusinessKey,
        Long contractId,
        Long agentId,
        Long commissionItemId,
        String commissionItemCode,
        String commissionItemName,
        BigDecimal amount,
        LocalDate settlementMonth,
        String cashflowType,
        LocalDate scheduledPaymentDate,
        PaymentStage paymentStage,
        CommissionPaymentStatus status,
        Long allocationPolicyVersion,
        List<CommissionPaymentAttributionResponse> attributions,
        List<Long> capCheckIds,
        Long journalHeaderId,
        String evidenceRef,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
