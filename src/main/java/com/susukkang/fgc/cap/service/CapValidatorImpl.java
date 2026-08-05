package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.util.MoneyUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class CapValidatorImpl implements CapValidator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    @Override
    public CapValidationResult validate(CapValidationRequest request) {
        BigDecimal limit = MoneyUtil.multiplyAndRound(
                request.basePremiumAmount(),
                request.premiumMultiplier()
        );
        BigDecimal candidate = request.inclusionDecisionStatus()
                == InclusionDecisionStatus.INCLUDED
                ? request.candidateAmount()
                : BigDecimal.ZERO;
        BigDecimal included = request.existingIncludedAmount().add(candidate);
        BigDecimal remaining = limit.subtract(included);
        BigDecimal usagePct = limit.signum() == 0
                ? (included.signum() == 0 ? BigDecimal.ZERO : ONE_HUNDRED)
                : included.multiply(ONE_HUNDRED)
                .divide(limit, 6, RoundingMode.HALF_UP);

        String resultStatus;
        if (remaining.signum() < 0) {
            resultStatus = "VIOLATION";
        } else if (usagePct.compareTo(request.warningUsagePct()) >= 0) {
            resultStatus = "WARNING";
        } else {
            resultStatus = "NORMAL";
        }

        return new CapValidationResult(
                limit,
                candidate,
                included,
                remaining,
                usagePct,
                resultStatus
        );
    }
}
