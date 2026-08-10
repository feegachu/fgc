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

        ResolvedCommissionPolicy policy =
                policyMapper.findApplicableCurrentCommissionPolicy(
                        contractId,
                        paymentStage
                );

        if (policy == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionPolicy",
                    Map.of(
                            "contractId", contractId,
                            "paymentStage", paymentStage.name()
                    ),
                    "계약에 적용할 현행 수수료 정책이 없습니다."
            );
        }

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
                            "paymentStage", paymentStage.name()
                    ),
                    "정책에 적용 가능한 수수료 규칙이 없습니다."
            );
        }

        return ResolvedCommissionPolicy.builder()
                .policyVersionId(policy.getPolicyVersionId())
                .policyType(policy.getPolicyType())
                .paymentStage(paymentStage)
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
