package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.DisplayFormat;

import java.time.LocalDate;

/**
 * cap_check 1건의 목록/게이지용 API 응답(SIR-008 준수) — IF-API-30(FUN-030) 전용.
 * 계산근거(cap_check_detail)는 담지 않는다 — IF-API-31(FUN-035, 강현준 담당)에서 capCheckId로 별도 조회한다
 * (공통규칙 #6). 계약 상세 탭의 IF-API-14(FUN-032, 박민준 담당)도 이 응답 모양을 참고하되 별도 이슈에서 구현한다.
 */
public record CapCheckItemResponse(
        Long capCheckId,
        Long contractId,
        String contractNo,
        PaymentStage paymentStage,
        String paymentStageLabel,
        LocalDate asOfDate,
        long basePremiumAmount,
        long refund12mAmount,
        long complianceDeductionAmount,
        long limitAmount,
        long includedAmount,
        long remainingAmount,
        String usagePct,
        CapResultStatus resultStatus,
        String resultStatusLabel,
        Long capRuleSetId
) {
    /** IF-API-30 목록 1행 — insurance_contract 조인으로 contractNo 를 이미 갖고 있다. */
    public static CapCheckItemResponse from(CapCheckListRow row) {
        PaymentStage stage = PaymentStage.valueOf(row.getPaymentStage());
        CapResultStatus status = CapResultStatus.valueOf(row.getResultStatus());
        return new CapCheckItemResponse(
                row.getCapCheckId(), row.getContractId(), row.getContractNo(),
                stage, stage.label(), row.getAsOfDate(),
                DisplayFormat.won(row.getBasePremiumAmount()), DisplayFormat.won(row.getRefund12mAmount()),
                DisplayFormat.won(row.getComplianceDeductionAmount()), DisplayFormat.won(row.getLimitAmount()),
                DisplayFormat.won(row.getIncludedAmount()), DisplayFormat.won(row.getRemainingAmount()),
                DisplayFormat.rate(row.getUsagePct()), status, status.label(), row.getCapRuleSetId());
    }
}
