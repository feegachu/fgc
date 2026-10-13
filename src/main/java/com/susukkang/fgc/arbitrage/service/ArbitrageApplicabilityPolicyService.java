package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 설명 : 승인된 차익거래 적용일 정책을 조회한다. 정책 미확인 시 임의 날짜로 판정하지 않는다.
 *
 * @author C4t4ddict
 * @since 2026-10-13
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class ArbitrageApplicabilityPolicyService {
    private final PolicyVersionRepository policyVersionRepository;

    @Transactional(readOnly = true)
    public Applicability resolve(LocalDate asOf) {
        var policies = policyVersionRepository.findArbitrageApplicability(asOf);
        if (policies.isEmpty()) return new Applicability(null, null, "차익거래 적용일 정책이 없습니다.");
        if (policies.size() != 1) return new Applicability(null, null, "차익거래 적용일 정책이 여러 건입니다.");
        var policy = policies.getFirst();
        try {
            if (policy.getEffectiveContractDate() == null) {
                return new Applicability(policy.getPolicyVersionId(), null, "차익거래 적용일 정책값이 없습니다.");
            }
            return new Applicability(policy.getPolicyVersionId(), LocalDate.parse(policy.getEffectiveContractDate()), null);
        } catch (DateTimeParseException e) {
            return new Applicability(policy.getPolicyVersionId(), null, "차익거래 적용일 정책값이 올바른 날짜가 아닙니다.");
        }
    }

    public record Applicability(Long policyVersionId, LocalDate effectiveContractDate, String reviewReason) { }
}
