package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.mapper.PolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    public ResolvedCommissionPolicy resolveCurrentCommission(Long contractId, PaymentStage paymentStage ) {
        //입력 파라미터 검증 , 계약 ID , INS->GA or GA->FC가 아닌지 체크
        validateQueryCondition(contractId, paymentStage);

        List<ResolvedCommissionPolicy> policies =
                policyMapper.findApplicableCurrentCommissionPolicies(contractId, paymentStage);
        //정책 검증
        //조회 결과 0건 → 적용 정책 없음
        //조회 결과 1건 → 정상
        //조회 결과 2건 이상 → 중복 활성 정책 오류
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
        //List에 하나 남은 정책을 가져와서 policy에 주입
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
                            "paymentStage", paymentStage.name()
                    ),
                    "정책에 적용 가능한 수수료 규칙이 없습니다."
            );
        }


        List<ResolvedCommissionRule> resolvedRules =
                resolveApplicableRules(rules, policy.getPolicyVersionId());

        return ResolvedCommissionPolicy.builder()
                .policyVersionId(policy.getPolicyVersionId())
                .policyType(policy.getPolicyType())
                .paymentStage(paymentStage)
                .scheduleRegime(policy.getScheduleRegime())
                .rules(resolvedRules)
                .build();
    }
    /**
     * 가져온 여러 RuleSet들 중 비슷한 유형끼리 그룹화를 하고
     *
     * @param candidateRules 계약 조건에 맞는 후보 수수료 규칙
     * @param policyVersionId 정책 버전 ID
     * @return 회차별로 확정된 최종 수수료 규칙 목록
     * @author hjKang
     * @since 2026-08-10
     */
    private List<ResolvedCommissionRule> resolveApplicableRules(
            List<ResolvedCommissionRule> candidateRules,
            Long policyVersionId
    ) {
        Map<RuleKey, List<ResolvedCommissionRule>> rulesByKey = new HashMap<>();

        /*
         * 모든 룰셋을 수수료 항목, 지급 대상 직급 및 회차별 RuleKey로 그룹화한다.
         *
         * 룰 A → RuleKey(기본수수료, FC, 1)
         * 룰 B → RuleKey(기본수수료, FC, 1)
         * 룰 C → RuleKey(관리수수료, SM, 1)
         * 룰 D → RuleKey(기본수수료, FC, 2)
         *
         * 결과: {A, B}, {C}, {D}
         */
        for (ResolvedCommissionRule rule : candidateRules) {
            // 룰셋 유효성 검증
            validateCandidateRule(rule, policyVersionId);

            for (int installmentNo = rule.getInstallmentFrom();
                 installmentNo <= rule.getInstallmentTo();
                 installmentNo++) {
                ResolvedCommissionRule installmentRule = rule.toBuilder()
                        .installmentFrom(installmentNo)
                        .installmentTo(installmentNo)
                        .build();

                RuleKey key = new RuleKey(
                        rule.getCommissionItemId(),
                        rule.getAgentRankCode(),
                        installmentNo
                );
                // 동일한 업무키의 규칙 목록이 없으면 새 목록을 생성한 후 현재 규칙을 추가
                rulesByKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(installmentRule);
            }
        }
        //
        List<ResolvedCommissionRule> resolvedRules = new ArrayList<>();
        for (Map.Entry<RuleKey, List<ResolvedCommissionRule>> entry : rulesByKey.entrySet()) {
            resolvedRules.add(selectMostApplicableRule(entry.getKey(), entry.getValue(), policyVersionId ));
        }

        resolvedRules.sort(
                Comparator.comparing(
                                ResolvedCommissionRule::getCommissionItemId,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparing(
                                ResolvedCommissionRule::getAgentRankCode,
                                Comparator.nullsFirst(Comparator.naturalOrder())
                        )
                        .thenComparing(ResolvedCommissionRule::getInstallmentFrom)
        );
        return List.copyOf(resolvedRules);
    }
    /** 수수료 항목, 지급 대상 직급 및 회차로 구성된 최종 규칙 선택 키. */
    private record RuleKey(
            Long commissionItemId,       //수수료 항목 ID
            AgentRankCode agentRankCode, //지급 대상 직급
            int installmentNo            //지급 회차
    ) { }

    /** 그룹 안에서 구체성(보험사,상품,조직 이 얼마나 명확한지)이 가장 높고 priorityNo가 가장 작은 규칙을 선택한다. */
    private ResolvedCommissionRule selectMostApplicableRule(
            RuleKey key,
            List<ResolvedCommissionRule> candidates,
            Long policyVersionId
    ) {
        int highestSpecificity = candidates.stream()
                .mapToInt(this::calculateSpecificity)
                .max()
                .orElseThrow();

        int highestPriority = candidates.stream()
                .filter(rule -> calculateSpecificity(rule) == highestSpecificity)
                .mapToInt(ResolvedCommissionRule::getPriorityNo)
                .min()
                .orElseThrow();

        List<ResolvedCommissionRule> winners = candidates.stream()
                .filter(rule -> calculateSpecificity(rule) == highestSpecificity)
                .filter(rule -> rule.getPriorityNo() == highestPriority)
                .toList();

        if (winners.size() > 1) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionRules",
                    Map.of(
                            "policyVersionId", policyVersionId,
                            "commissionItemId", key.commissionItemId(),
                            "agentRankCode", String.valueOf(key.agentRankCode()),
                            "installmentNo", key.installmentNo(),
                            "reason", "RULE_DUPLICATE"
                    ),
                    "동일한 우선순위와 구체성을 가진 수수료 규칙이 여러 건 존재합니다."
            );
        }

        return winners.getFirst();
    }

    /** 보험회사, 상품 판매버전, 조직 조건 중 지정된 조건의 개수를 반환한다. */
    private int calculateSpecificity(ResolvedCommissionRule rule) {
        int specificity = 0;
        if (rule.getInsurerId() != null) {
            specificity++;
        }
        if (rule.getProductOfferingId() != null) {
            specificity++;
        }
        if (rule.getOrganizationId() != null) {
            specificity++;
        }
        return specificity;
    }

    /** 룰셋 컬럼값 이상 여부 검증. */
    private void validateCandidateRule(
            ResolvedCommissionRule rule,
            Long policyVersionId
    ) {
        if (rule == null
                || rule.getCommissionRuleId() == null
                || rule.getCommissionItemId() == null
                || rule.getInstallmentFrom() == null
                || rule.getInstallmentTo() == null
                || rule.getInstallmentFrom() < 1
                || rule.getInstallmentTo() < rule.getInstallmentFrom()
                || rule.getPriorityNo() == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_002,
                    "commissionRules",
                    Map.of(
                            "policyVersionId", String.valueOf(policyVersionId),
                            "reason", "INVALID_RULE"
                    ),
                    "수수료 규칙의 필수값 또는 회차 범위가 올바르지 않습니다."
            );
        }
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
