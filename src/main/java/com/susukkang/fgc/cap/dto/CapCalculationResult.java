package com.susukkang.fgc.cap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 한도 계산 결과와 감사 재현용 계산 스냅샷
 * cap_check 1행 + cap_check_detail 목록에 대응하는 값을 담지만,
 * 이 자체로는 아직 저장되지 않은 상태다 — 저장은 CapCheckService(오케스트레이션 계층)의 책임이다.
 *
 * basePremiumAmount는 월납환산 초회보험료 원액이다(×12 하지 않음 — 화면정의서 CAP-W01
 * "기준 보험료"/CAP-W02 "① 입력값 - 월납환산 초회보험료"). 한도식의 연간 기준액(×premium_multiplier)은
 * 저장·노출하지 않고 CapCalculatorImpl 내부에서만 쓴다.
 *
 * limitAmount 계산 순서:
 *   annualizedLimitBase = basePremiumAmount × premium_multiplier(기본 12)
 *   refund12mAmount = 80% 이상 공제 대상일 때만, annualizedLimitBase × 12차월 예상 해약환급률
 *   complianceDeductionAmount = min(증빙된 실제 준법경영비, basePremiumAmount × 최대 허용률)
 *   limitAmount = max(0, annualizedLimitBase - refund12mAmount + complianceDeductionAmount)
 *
 * 규정 판정식은 "산입액 + refund12mAmount - complianceDeductionAmount ≤ annualizedLimitBase"다
 * (감독규정 제4-32조⑤·⑭, FAQ 2차 Q1-1). 이를 한도 쪽으로 이항해 includedAmount는 상세 INCLUDED 합계 그대로 둔다.
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
public record CapCalculationResult(
        Long contractId,
        PaymentStage paymentStage,
        CapCheckKind checkKind,
        LocalDate asOfDate,
        @Schema(types = {"integer", "null"})
        Long capRuleSetId,
        Long refundRateTableId,
        BigDecimal basePremiumAmount,
        @Schema(types = {"number", "null"})
        BigDecimal refund12mAmount,
        @Schema(types = {"number", "null"})
        BigDecimal complianceDeductionAmount,
        @Schema(types = {"number", "null"})
        BigDecimal limitAmount,
        @Schema(types = {"number", "null"})
        BigDecimal includedAmount,
        @Schema(types = {"number", "null"})
        BigDecimal remainingAmount,
        @Schema(types = {"number", "null"})
        BigDecimal usagePct,
        CapResultStatus resultStatus,
        List<CapCheckDetailLine> details,
        Map<String, Object> calculationSnapshot
) {
    /**
     * 연간 기준액 L(월납 × 배수). limit = L − 해약환급금 + 준법경영비 이므로 저장된 세 값에서 되돌린다 (#490).
     * limit이 max(0)으로 잘린 경우(환급률 100% 초과)만 실제 L과 다른데, 그 판정은 REVIEW_REQUIRED가 우선한다.
     */
    public BigDecimal annualizedLimitBase() {
        return resultStatus == CapResultStatus.NOT_APPLICABLE ? null
                : limitAmount.add(refund12mAmount).subtract(complianceDeductionAmount);
    }
}
