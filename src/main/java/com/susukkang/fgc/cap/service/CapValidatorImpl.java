package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 설명 : 실제 지급액을 기존 산입액에 더해 1,200% 한도 상태를 판정하는 구현체
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@Service
public class CapValidatorImpl implements CapValidator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    @Override
    public CapValidationResult validate(CapValidationRequest request) {
        // 2026-08-06 yslee - 규제 한도 계산 책임을 CapCalculator로 단일화
        // 기존 코드: CapValidator가 월납보험료와 배수를 이용해 한도를 직접 계산
        // 문제: CapCalculator의 환급금·정책 버전 계산과 다른 결과가 발생할 수 있음
        // 개선: CapCalculator가 산출한 최종 한도를 전달받아 실제 지급 후보만 판정
        BigDecimal limit = request.limitAmount();
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
