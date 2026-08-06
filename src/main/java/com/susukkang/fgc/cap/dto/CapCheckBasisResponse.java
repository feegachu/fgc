package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * IF-API-31(계산근거 팝업) 응답. cap_check 저장 당시의 계산 스냅샷을 그대로 보여줌
 *
 *   ① 입력값   - basePremiumAmount/refund12mAmount/complianceDeductionAmount + calculationSnapshot
 *              (premiumMultiplier/refundAdditionCondition/complianceDeductionPct)
 *   ② 계산식   - basePremiumAmount~usagePct 숫자 그대로. 수식 문자열은 화면에서 조립
 *   ③ 항목별   - details(cap_check_detail 스냅샷)
 *   ④ 합계/판정 - includedAmount/limitAmount/remainingAmount/usagePct/resultStatus
 *
 * basePremiumAmount는 월납환산 초회보험료 원액이다(화면정의서 CAP-W01 "기준 보험료"/CAP-W02
 * "① 입력값 - 월납환산 초회보험료"). ×premiumMultiplier(기본 12) 한 값은 limitAmount 계산에만
 * 쓰이고 별도로 노출되지 않는다 — 화면 ②계산식은 basePremiumAmount ×
 * calculationSnapshot.premiumMultiplier로 조립한다.
 */
public record CapCheckBasisResponse(
        Long capCheckId,
        Long contractId,
        String contractNo,
        String paymentStage,
        String checkKind,
        LocalDate asOfDate,
        Long capRuleSetId,
        BigDecimal basePremiumAmount,
        BigDecimal refund12mAmount,
        BigDecimal complianceDeductionAmount,
        BigDecimal limitAmount,
        BigDecimal includedAmount,
        BigDecimal remainingAmount,
        BigDecimal usagePct,
        String resultStatus,
        Map<String, Object> calculationSnapshot,
        List<CapCheckDetailLine> details,
        BigDecimal detailIncludedSum
) {
    public static CapCheckBasisResponse from(CapCheckSaveResult saved, String contractNo) {
        CapCalculationResult r = saved.result();

        BigDecimal detailIncludedSum = r.details().stream()
                .filter(d -> "INCLUDED".equals(d.classification()))
                .map(CapCheckDetailLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CapCheckBasisResponse(
                saved.capCheckId(),
                r.contractId(),
                contractNo,
                r.paymentStage().name(),
                r.checkKind().name(),
                r.asOfDate(),
                r.capRuleSetId(),
                r.basePremiumAmount(),
                r.refund12mAmount(),
                r.complianceDeductionAmount(),
                r.limitAmount(),
                r.includedAmount(),
                r.remainingAmount(),
                r.usagePct(),
                r.resultStatus().name(),
                r.calculationSnapshot(),
                r.details(),
                detailIncludedSum
        );
    }
}
