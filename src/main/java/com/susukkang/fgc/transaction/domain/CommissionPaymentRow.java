package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 조회 데이터
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
// 2026-08-11 yslee - 지급 건 조회행에 부모 증빙 참조를 포함
// 기존 코드: commission_transaction.evidence_ref를 조회 DTO가 받을 필드가 없음
// 문제: DB 저장 후 API 응답 변환 과정에서 지급 건 증빙이 소실
// 개선: evidenceRef를 조회행과 응답 변환에 연결
public record CommissionPaymentRow(
        Long paymentId,
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
        String evidenceRef,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public CommissionPaymentResponse toResponse(
            List<CommissionPaymentAttributionRow> attributions,
            List<Long> capCheckIds
    ) {
        return new CommissionPaymentResponse(
                paymentId,
                sourceType,
                sourceBusinessKey,
                contractId,
                agentId,
                commissionItemId,
                commissionItemCode,
                commissionItemName,
                amount,
                settlementMonth,
                cashflowType,
                scheduledPaymentDate,
                paymentStage,
                status,
                allocationPolicyVersion,
                attributions.stream().map(CommissionPaymentAttributionRow::toResponse).toList(),
                List.copyOf(capCheckIds),
                // 2026-08-11 yslee - 분개 원장 식별자의 후속 연동 경계를 명시
                // 기존 코드: journalHeaderId에 설명 없이 항상 null을 반환
                // 문제: FUN-065 누락인지 FUN-046 미연동 상태인지 API 응답만으로 구분하기 어려움
                // 개선: FUN-046 복식부기 분개 생성 완료 후 연결할 통합 지점으로 유지
                null,
                evidenceRef,
                note,
                createdAt,
                updatedAt
        );
    }
}
