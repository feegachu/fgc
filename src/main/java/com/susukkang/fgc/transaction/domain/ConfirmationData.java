package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 지급 확정 및 한도 검증에 필요한 잠금 조회 데이터
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
public record ConfirmationData(
        Long paymentId,
        CommissionPaymentStatus status,
        BigDecimal amount,
        BigDecimal attributedAmount,
        BigDecimal totalAttributedAmount,
        String confirmIdempotencyKey,
        LocalDate attributionDate,
        LocalDate attributionMonth,
        Long transactionAttributionId,
        Long contractId,
        Long agentId,
        PaymentStage paymentStage,
        Long commissionItemId,
        String itemCode,
        String itemName,
        Long policyVersionId,
        InclusionDecisionStatus inclusionDecisionStatus,
        ExclusionType exclusionType,
        String inclusionDecisionReason,
        String allocationBasis,
        String evidenceRef,
        AttributionMethod attributionMethod,
        LocalDate contractDate
) {

    /**
     * 기존 단위 테스트와 호출부 호환용 생성자입니다. DB 조회에서는 canonical 생성자에
     * contract_date가 매핑되고, 직접 만든 테스트 데이터는 계약일 미확인 상태로 둡니다.
     */
    public ConfirmationData(
            Long paymentId, CommissionPaymentStatus status, BigDecimal amount, BigDecimal attributedAmount,
            BigDecimal totalAttributedAmount, String confirmIdempotencyKey, LocalDate attributionDate,
            LocalDate attributionMonth, Long transactionAttributionId, Long contractId, Long agentId,
            PaymentStage paymentStage, Long commissionItemId, String itemCode, String itemName,
            Long policyVersionId, InclusionDecisionStatus inclusionDecisionStatus, ExclusionType exclusionType,
            String inclusionDecisionReason, String allocationBasis, String evidenceRef,
            AttributionMethod attributionMethod
    ) {
        this(paymentId, status, amount, attributedAmount, totalAttributedAmount, confirmIdempotencyKey,
                attributionDate, attributionMonth, transactionAttributionId, contractId, agentId, paymentStage,
                commissionItemId, itemCode, itemName, policyVersionId, inclusionDecisionStatus, exclusionType,
                inclusionDecisionReason, allocationBasis, evidenceRef, attributionMethod, null);
    }
}
