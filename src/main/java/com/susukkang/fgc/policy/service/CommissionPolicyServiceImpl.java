package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.mapper.PolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 설명 : 계약과 지급 단계에 적용할 현행 수수료 정책을 조회하는 서비스 구현체
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommissionPolicyServiceImpl implements CommissionPolicyService {

    private final PolicyMapper policyMapper;

    /**
     * 설명 : 계약과 지급 단계에 적용할 현행 수수료 정책과 규칙 목록을 조회한다.
     * 적용 가능한 활성 정책이 정확히 한 건인 경우에만 수수료 규칙을 조회한다.
     * 정책이 없거나 복수로 조회되면 스케줄 생성을 중단할 수 있도록 예외를 발생시킨다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 적용된 정책 버전과 수수료 규칙 목록
     */
    @Override
    public ResolvedCommissionPolicy resolveCurrentCommission(
            Long contractId,
            PaymentStage paymentStage
    ) {
        validateQueryCondition(contractId, paymentStage);

        List<ResolvedCommissionPolicy> policies =
                policyMapper.findApplicableCurrentCommissionPolicies(
                        contractId,
                        paymentStage
                );

        if (policies == null || policies.isEmpty()) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionPolicy",
                    Map.of(
                            "contractId", contractId,
                            "paymentStage", paymentStage.name(),
                            "reason", "POLICY_MISSING"
                    ),
                    "계약에 적용할 현행 수수료 정책이 없습니다."
            );
        }

        if (policies.size() > 1) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionPolicy",
                    Map.of(
                            "contractId", contractId,
                            "paymentStage", paymentStage.name(),
                            "reason", "POLICY_DUPLICATE",
                            "policyCount", policies.size()
                    ),
                    "계약에 적용 가능한 현행 수수료 정책이 여러 건 존재합니다."
            );
        }

        ResolvedCommissionPolicy policy = policies.getFirst();

        List<ResolvedCommissionRule> rules =
                policyMapper.findApplicableCommissionRules(
                        policy.getPolicyVersionId(),
                        contractId,
                        paymentStage
                );

        if (rules == null || rules.isEmpty()) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionRules",
                    Map.of(
                            "policyVersionId", policy.getPolicyVersionId(),
                            "paymentStage", paymentStage.name(),
                            "reason", "POLICY_MISSING"
                    ),
                    "정책에 적용 가능한 수수료 규칙이 없습니다."
            );
        }

        return ResolvedCommissionPolicy.builder()
                .policyVersionId(policy.getPolicyVersionId())
                .policyType(policy.getPolicyType())
                .paymentStage(paymentStage)
                .scheduleRegime(policy.getScheduleRegime())
                .rules(List.copyOf(rules))
                .build();
    }

    /**
     * 설명 : 현행 수수료 정책 조회에 필요한 계약 ID와 지급 단계를 검증한다.
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     */
    private void validateQueryCondition(
            Long contractId,
            PaymentStage paymentStage
    ) {
        if (contractId == null || paymentStage == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionPolicy",
                    Map.of(
                            "contractId", String.valueOf(contractId),
                            "paymentStage", String.valueOf(paymentStage)
                    ),
                    "수수료 정책 조회 조건이 올바르지 않습니다."
            );
        }
    }
}
