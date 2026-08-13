package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 한도 검증 결과에 따라 예외 건을 생성하기 위한 명령 객체
 *
 * @param paymentId 지급 건 ID
 * @param contractId 관련 계약 ID
 * @param agentId 관련 설계사 ID
 * @param policyVersionId 적용 정책 버전 ID
 * @param validationRunId 월 검증 실행 ID
 * @param paymentStage 지급 단계
 * @param asOfDate 한도 검증 기준일
 * @param capCheckId 한도 검증 결과 ID
 * @param capRuleSetId 적용 한도 룰셋 ID
 * @param refundRateTableId 적용 환급률표 ID
 * @param basePremiumAmount 한도 계산 기준 보험료
 * @param refund12mAmount 12개월 환급금 가산액
 * @param complianceDeductionAmount 준법경영비 차감액
 * @param limitAmount 최종 한도액
 * @param includedAmount 한도 산입액
 * @param remainingAmount 잔여 한도액
 * @param usagePct 한도 사용률
 * @param resultStatus 한도 검증 결과 상태
 * @param calculationSnapshot 한도 계산 근거 스냅샷 JSON
 * @author hjKang
 * @since 2026-08-12
 */
@Builder
public record CapExceptionCreateCommand(
        Long paymentId,
        Long contractId,
        Long agentId,
        Long policyVersionId,
        Long validationRunId,
        PaymentStage paymentStage,
        LocalDate asOfDate,
        Long capCheckId,
        Long capRuleSetId,
        Long refundRateTableId,
        BigDecimal basePremiumAmount,
        BigDecimal refund12mAmount,
        BigDecimal complianceDeductionAmount,
        BigDecimal limitAmount,
        BigDecimal includedAmount,
        BigDecimal remainingAmount,
        BigDecimal usagePct,
        CapResultStatus resultStatus,
        String calculationSnapshot
) {}