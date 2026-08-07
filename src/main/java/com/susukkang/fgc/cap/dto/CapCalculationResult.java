package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * CapCalculator 순수 계산 결과. cap_check 1행 + cap_check_detail 목록에 대응하는 값을 담지만,
 * 이 자체로는 아직 저장되지 않은 상태다 — 저장은 CapCheckService(오케스트레이션 계층)의 책임이다.
 *
 * basePremiumAmount는 월납환산 초회보험료 원액이다(×12 하지 않음 — 화면정의서 CAP-W01
 * "기준 보험료"/CAP-W02 "① 입력값 - 월납환산 초회보험료"). 한도식의 연간 기준액(×premium_multiplier)은
 * 저장·노출하지 않고 CapCalculatorImpl 내부에서만 쓴다.
 *
 * limitAmount 계산 순서:
 *   annualizedLimitBase = basePremiumAmount × premium_multiplier(기본 12)
 *   refund12mAmount = 80% 이상 공제 대상일 때만, annualizedLimitBase × 12차월 예상 해약환급률
 *   grossLimit = annualizedLimitBase + refund12mAmount
 *   complianceDeductionAmount = basePremiumAmount × compliance_deduction_pct (REG-10: 월납 기준
 *     초회보험료의 3% — grossLimit이 아니라 basePremiumAmount 기준. INSURER_TO_GA만 0보다 큼)
 *   limitAmount = grossLimit - complianceDeductionAmount
 */
public record CapCalculationResult(
        Long contractId,
        PaymentStage paymentStage,
        CapCheckKind checkKind,
        LocalDate asOfDate,
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
        List<CapCheckDetailLine> details,
        Map<String, Object> calculationSnapshot
) {
}
