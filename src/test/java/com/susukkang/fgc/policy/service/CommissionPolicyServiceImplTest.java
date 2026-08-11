package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.FeeComponentType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.mapper.PolicyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CommissionPolicyServiceImplTest {

    @Mock
    private PolicyMapper policyMapper;

    @InjectMocks
    private CommissionPolicyServiceImpl commissionPolicyService;

    @Test
    void resolvesApplicablePolicyAndRules() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;

        ResolvedCommissionPolicy policy =
                ResolvedCommissionPolicy.builder()
                        .policyVersionId(policyVersionId)
                        .policyType(PolicyType.CURRENT_COMMISSION)
                        .paymentStage(paymentStage)
                        .build();

        ResolvedCommissionRule rule =
                validRule().toBuilder()
                        .commissionRuleId(1000L)
                        .commissionItemId(2000L)
                        .agentRankCode(AgentRankCode.FC)
                        .installmentFrom(1)
                        .installmentTo(1)
                        .priorityNo(100)
                        .build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId,
                paymentStage
        )).willReturn(List.of(policy));

        given(policyMapper.findApplicableCommissionRules(
                policyVersionId,
                contractId,
                paymentStage
        )).willReturn(List.of(rule));

        ResolvedCommissionPolicy result =
                commissionPolicyService.resolveCurrentCommission(
                        contractId,
                        paymentStage
                );

        assertThat(result.getPolicyVersionId()).isEqualTo(policyVersionId);
        assertThat(result.getPolicyType())
                .isEqualTo(PolicyType.CURRENT_COMMISSION);
        assertThat(result.getPaymentStage()).isEqualTo(paymentStage);
        assertThat(result.getRules())
                .extracting(ResolvedCommissionRule::getCommissionRuleId)
                .containsExactly(rule.getCommissionRuleId());

        verify(policyMapper).findApplicableCurrentCommissionPolicies(
                contractId,
                paymentStage
        );
        verify(policyMapper).findApplicableCommissionRules(
                policyVersionId,
                contractId,
                paymentStage
        );
    }

    @Test
    void rejectsMissingQueryCondition() {
        assertThatThrownBy(() ->
                commissionPolicyService.resolveCurrentCommission(
                        null,
                        PaymentStage.GA_TO_FC
                ))
                .isInstanceOf(FgcBusinessException.class);

        verifyNoInteractions(policyMapper);
    }

    @Test
    void rejectsWhenApplicablePolicyDoesNotExist() {
        Long contractId = 10L;
        PaymentStage paymentStage = PaymentStage.INSURER_TO_GA;

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId,
                paymentStage
        )).willReturn(List.of());

        assertThatThrownBy(() ->
                commissionPolicyService.resolveCurrentCommission(
                        contractId,
                        paymentStage
                ))
                .isInstanceOf(FgcBusinessException.class);

        verify(policyMapper, never()).findApplicableCommissionRules(
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsWhenApplicablePoliciesAreDuplicated() {
        Long contractId = 10L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;

        ResolvedCommissionPolicy firstPolicy =
                ResolvedCommissionPolicy.builder()
                        .policyVersionId(100L)
                        .policyType(PolicyType.CURRENT_COMMISSION)
                        .paymentStage(paymentStage)
                        .build();

        ResolvedCommissionPolicy secondPolicy =
                ResolvedCommissionPolicy.builder()
                        .policyVersionId(101L)
                        .policyType(PolicyType.CURRENT_COMMISSION)
                        .paymentStage(paymentStage)
                        .build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId,
                paymentStage
        )).willReturn(List.of(firstPolicy, secondPolicy));

        assertThatThrownBy(() ->
                commissionPolicyService.resolveCurrentCommission(
                        contractId,
                        paymentStage
                ))
                .isInstanceOf(FgcBusinessException.class);

        verify(policyMapper, never()).findApplicableCommissionRules(
                any(),
                any(),
                any()
        );
    }

    @Test
    void rejectsWhenApplicableRulesDoNotExist() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;

        ResolvedCommissionPolicy policy =
                ResolvedCommissionPolicy.builder()
                        .policyVersionId(policyVersionId)
                        .policyType(PolicyType.CURRENT_COMMISSION)
                        .paymentStage(paymentStage)
                        .build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId,
                paymentStage
        )).willReturn(List.of(policy));

        given(policyMapper.findApplicableCommissionRules(
                policyVersionId,
                contractId,
                paymentStage
        )).willReturn(List.of());

        assertThatThrownBy(() ->
                commissionPolicyService.resolveCurrentCommission(
                        contractId,
                        paymentStage
                ))
                .isInstanceOf(FgcBusinessException.class);
    }

    @Test
    void prefersOrganizationSpecificRuleOnlyForItsInstallmentRange() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder()
                .policyVersionId(policyVersionId)
                .policyType(PolicyType.CURRENT_COMMISSION)
                .paymentStage(paymentStage)
                .build();
        ResolvedCommissionRule generalRule = validRule().toBuilder()
                .commissionRuleId(1000L)
                .commissionItemId(2000L)
                .agentRankCode(AgentRankCode.FC)
                .installmentFrom(1)
                .installmentTo(3)
                .priorityNo(100)
                .build();
        ResolvedCommissionRule organizationRule = generalRule.toBuilder()
                .commissionRuleId(1001L)
                .organizationId(3000L)
                .installmentFrom(1)
                .installmentTo(1)
                .build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId, paymentStage)).willReturn(List.of(policy));
        given(policyMapper.findApplicableCommissionRules(
                policyVersionId, contractId, paymentStage))
                .willReturn(List.of(generalRule, organizationRule));

        ResolvedCommissionPolicy result =
                commissionPolicyService.resolveCurrentCommission(
                        contractId, paymentStage);

        assertThat(result.getRules())
                .extracting(ResolvedCommissionRule::getCommissionRuleId)
                .containsExactly(1001L, 1000L, 1000L);
        assertThat(result.getRules())
                .extracting(ResolvedCommissionRule::getInstallmentFrom)
                .containsExactly(1, 2, 3);
        assertThat(result.getRules())
                .allMatch(rule -> rule.getInstallmentFrom()
                        .equals(rule.getInstallmentTo()));
    }

    @Test
    void prioritizesInsurerRuleOverOrganizationRuleRegardlessOfPriorityNumber() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder()
                .policyVersionId(policyVersionId)
                .build();
        ResolvedCommissionRule insurerRule = validRule().toBuilder()
                .commissionRuleId(1000L)
                .commissionItemId(2000L)
                .agentRankCode(AgentRankCode.FC)
                .installmentFrom(1)
                .installmentTo(1)
                .insurerId(1L)
                .priorityNo(20)
                .build();
        ResolvedCommissionRule organizationRule = insurerRule.toBuilder()
                .commissionRuleId(1001L)
                .insurerId(null)
                .organizationId(2L)
                .priorityNo(10)
                .build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId, paymentStage)).willReturn(List.of(policy));
        given(policyMapper.findApplicableCommissionRules(
                policyVersionId, contractId, paymentStage))
                .willReturn(List.of(insurerRule, organizationRule));

        ResolvedCommissionPolicy result =
                commissionPolicyService.resolveCurrentCommission(
                        contractId, paymentStage);

        assertThat(result.getRules())
                .extracting(ResolvedCommissionRule::getCommissionRuleId)
                .containsExactly(1000L);
    }

    @Test
    void prioritizesProductOfferingRuleOverInsurerRuleRegardlessOfPriorityNumber() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder().policyVersionId(policyVersionId).build();
        ResolvedCommissionRule productOfferingRule = validRule().toBuilder().commissionRuleId(1000L).commissionItemId(2000L).agentRankCode(AgentRankCode.FC).installmentFrom(1).installmentTo(1).productOfferingId(3L).priorityNo(20).build();
        ResolvedCommissionRule insurerRule = productOfferingRule.toBuilder().commissionRuleId(1001L).productOfferingId(null).insurerId(1L).priorityNo(10).build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(contractId, paymentStage)).willReturn(List.of(policy));
        given(policyMapper.findApplicableCommissionRules(policyVersionId, contractId, paymentStage)).willReturn(List.of(productOfferingRule, insurerRule));

        ResolvedCommissionPolicy result = commissionPolicyService.resolveCurrentCommission(contractId, paymentStage);

        assertThat(result.getRules()).extracting(ResolvedCommissionRule::getCommissionRuleId).containsExactly(1000L);
    }

    @Test
    void preservesGeneralAndSpecificAgentRankRulesAsDifferentPaymentTargets() {
        ResolvedCommissionRule generalRule = validRule().toBuilder().commissionRuleId(1000L).agentRankCode(null).priorityNo(10).build();
        ResolvedCommissionRule fcRule = generalRule.toBuilder().commissionRuleId(1001L).agentRankCode(AgentRankCode.FC).priorityNo(20).build();

        ResolvedCommissionPolicy result = resolveRules(List.of(generalRule, fcRule));

        assertThat(result.getRules()).extracting(ResolvedCommissionRule::getCommissionRuleId).containsExactly(1000L, 1001L);
    }

    @Test
    void preservesManagementRulesForEachAgentRank() {
        ResolvedCommissionRule teamLeaderRule = validRule().toBuilder().commissionRuleId(1000L).agentRankCode(AgentRankCode.TEAM_LEADER).priorityNo(100).build();
        ResolvedCommissionRule branchManagerRule = teamLeaderRule.toBuilder().commissionRuleId(1001L).agentRankCode(AgentRankCode.BRANCH_MANAGER).build();
        ResolvedCommissionRule divisionHeadRule = teamLeaderRule.toBuilder().commissionRuleId(1002L).agentRankCode(AgentRankCode.DIVISION_HEAD).build();

        ResolvedCommissionPolicy result = resolveRules(List.of(teamLeaderRule, branchManagerRule, divisionHeadRule));

        assertThat(result.getRules()).extracting(ResolvedCommissionRule::getAgentRankCode)
                .containsExactly(AgentRankCode.TEAM_LEADER, AgentRankCode.BRANCH_MANAGER, AgentRankCode.DIVISION_HEAD);
    }

    @Test
    void rejectsRulesWithSameSelectionOrderAndPriority() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder()
                .policyVersionId(policyVersionId)
                .build();
        ResolvedCommissionRule firstRule = validRule().toBuilder()
                .commissionRuleId(1000L)
                .commissionItemId(2000L)
                .agentRankCode(AgentRankCode.FC)
                .installmentFrom(1)
                .installmentTo(1)
                .insurerId(1L)
                .priorityNo(10)
                .build();
        ResolvedCommissionRule secondRule = firstRule.toBuilder()
                .commissionRuleId(1001L)
                .build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(
                contractId, paymentStage)).willReturn(List.of(policy));
        given(policyMapper.findApplicableCommissionRules(
                policyVersionId, contractId, paymentStage))
                .willReturn(List.of(firstRule, secondRule));

        assertThatThrownBy(() ->
                commissionPolicyService.resolveCurrentCommission(
                        contractId, paymentStage))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception -> assertThat(
                        ((FgcBusinessException) exception).getParams())
                        .containsEntry("reason", "RULE_DUPLICATE"));
    }

    @Test
    void rejectsRuleWhenInstallmentRangeExceedsSupportedLimit() {
        assertInvalidRule(validRule().toBuilder().installmentTo(85).build());
    }

    @Test
    void rejectsRuleWhenInstallmentStartsBelowOne() {
        assertInvalidRule(validRule().toBuilder().installmentFrom(0).build());
    }

    @Test
    void rejectsRuleWhenInstallmentRangeIsReversed() {
        assertInvalidRule(validRule().toBuilder().installmentFrom(2).installmentTo(1).build());
    }

    @Test
    void acceptsRuleAtMaximumSupportedInstallment() {
        ResolvedCommissionPolicy result = resolveSingleRule(validRule().toBuilder().installmentFrom(84).installmentTo(84).build());
        assertThat(result.getRules()).extracting(ResolvedCommissionRule::getInstallmentFrom).containsExactly(84);
    }

    @Test
    void rejectsRuleWhenPriorityIsMissing() {
        assertInvalidRule(validRule().toBuilder().priorityNo(null).build());
    }

    @Test
    void rejectsRuleWhenFeeComponentTypeIsMissing() {
        assertInvalidRule(validRule().toBuilder().feeComponentType(null).build());
    }

    @Test
    void rejectsRuleWhenRequiredCalculationFieldIsMissing() {
        assertInvalidRule(validRule().toBuilder().roundingMode(null).build());
    }

    @Test
    void rejectsRateRuleWhenFixedAmountIsAlsoPresent() {
        assertInvalidRule(validRule().toBuilder().fixedAmount(BigDecimal.ONE).build());
    }

    @Test
    void rejectsFixedRuleWhenRateIsAlsoPresent() {
        ResolvedCommissionRule rule = validRule().toBuilder().calculationType(CalculationType.FIXED).fixedAmount(BigDecimal.ONE).ratePct(BigDecimal.ONE).build();
        assertInvalidRule(rule);
    }

    @Test
    void acceptsFixedRuleWithNonNegativeFixedAmount() {
        ResolvedCommissionRule rule = validRule().toBuilder().calculationType(CalculationType.FIXED).fixedAmount(BigDecimal.ZERO).ratePct(null).build();
        ResolvedCommissionPolicy result = resolveSingleRule(rule);
        assertThat(result.getRules()).extracting(ResolvedCommissionRule::getCalculationType).containsExactly(CalculationType.FIXED);
    }

    @Test
    void rejectsPolicyWhenPolicyVersionIdIsMissing() {
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder().policyVersionId(null).build();
        given(policyMapper.findApplicableCurrentCommissionPolicies(10L, PaymentStage.GA_TO_FC)).willReturn(List.of(policy));

        assertThatThrownBy(() -> commissionPolicyService.resolveCurrentCommission(10L, PaymentStage.GA_TO_FC))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception -> assertThat(((FgcBusinessException) exception).getParams()).containsEntry("reason", "INVALID_POLICY"));

        verify(policyMapper, never()).findApplicableCommissionRules(any(), any(), any());
    }

    // 정상 규칙의 공통 필드를 생성해 각 테스트가 검증 대상만 변경하도록 한다.
    private ResolvedCommissionRule validRule() {
        return ResolvedCommissionRule.builder().commissionRuleId(1000L).commissionItemId(2000L).feeComponentType(FeeComponentType.CURRENT).agentRankCode(AgentRankCode.FC).installmentFrom(1).installmentTo(1).basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM").calculationType(CalculationType.RATE).ratePct(BigDecimal.ONE).roundingScale(0).roundingMode(RoundingMode.HALF_UP).priorityNo(100).build();
    }

    // 단일 후보 규칙의 유효성 검증 실패를 공통 시나리오로 확인한다.
    private void assertInvalidRule(ResolvedCommissionRule rule) {
        assertThatThrownBy(() -> resolveSingleRule(rule))
                .isInstanceOf(FgcBusinessException.class)
                .satisfies(exception -> assertThat(((FgcBusinessException) exception).getParams()).containsEntry("reason", "INVALID_RULE"));
    }

    // 단일 후보 규칙을 조회하는 공통 정책 시나리오를 실행한다.
    private ResolvedCommissionPolicy resolveSingleRule(ResolvedCommissionRule rule) {
        return resolveRules(List.of(rule));
    }

    // 여러 후보 규칙을 조회하는 공통 정책 시나리오를 실행한다.
    private ResolvedCommissionPolicy resolveRules(List<ResolvedCommissionRule> rules) {
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder().policyVersionId(100L).build();
        given(policyMapper.findApplicableCurrentCommissionPolicies(10L, PaymentStage.GA_TO_FC)).willReturn(List.of(policy));
        given(policyMapper.findApplicableCommissionRules(100L, 10L, PaymentStage.GA_TO_FC)).willReturn(rules);
        return commissionPolicyService.resolveCurrentCommission(10L, PaymentStage.GA_TO_FC);
    }
}
