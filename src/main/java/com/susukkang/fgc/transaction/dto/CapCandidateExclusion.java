package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.MoneyUtil;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import java.time.LocalDate;

/** 후보 귀속의 기간 제외 근거. 계약 전체의 최신 한도 판정을 대체하지 않는다. */
public record CapCandidateExclusion(
        Long transactionAttributionId, Long contractId, PaymentStage paymentStage,
        LocalDate attributionDate, LocalDate firstYearStartInclusive, LocalDate firstYearEndExclusive,
        long excludedAmount, CapResultStatus resultStatus, String reasonCode, String reason
) {
    public static final String REASON = "초년도 밖 — 판정 대상 아님";

    public static boolean isOutsideFirstYear(ConfirmationData data) {
        return data.contractId() != null && data.contractDate() != null && data.attributionDate() != null
                && !data.attributionDate().isBefore(data.contractDate().plusMonths(12));
    }

    public static CapCandidateExclusion from(ConfirmationData data) {
        return new CapCandidateExclusion(data.transactionAttributionId(), data.contractId(), data.paymentStage(),
                data.attributionDate(), data.contractDate(), data.contractDate().plusMonths(12),
                data.attributedAmount() == null ? 0 : MoneyUtil.roundWon(data.attributedAmount()).longValueExact(),
                CapResultStatus.NOT_APPLICABLE, "OUTSIDE_FIRST_YEAR", REASON);
    }
}
