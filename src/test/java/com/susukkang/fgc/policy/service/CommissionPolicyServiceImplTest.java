package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.mapper.PolicyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                ResolvedCommissionRule.builder()
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
        ResolvedCommissionRule generalRule = ResolvedCommissionRule.builder()
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
        ResolvedCommissionRule insurerRule = ResolvedCommissionRule.builder()
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
        ResolvedCommissionRule productOfferingRule = ResolvedCommissionRule.builder().commissionRuleId(1000L).commissionItemId(2000L).agentRankCode(AgentRankCode.FC).installmentFrom(1).installmentTo(1).productOfferingId(3L).priorityNo(20).build();
        ResolvedCommissionRule insurerRule = productOfferingRule.toBuilder().commissionRuleId(1001L).productOfferingId(null).insurerId(1L).priorityNo(10).build();

        given(policyMapper.findApplicableCurrentCommissionPolicies(contractId, paymentStage)).willReturn(List.of(policy));
        given(policyMapper.findApplicableCommissionRules(policyVersionId, contractId, paymentStage)).willReturn(List.of(productOfferingRule, insurerRule));

        ResolvedCommissionPolicy result = commissionPolicyService.resolveCurrentCommission(contractId, paymentStage);

        assertThat(result.getRules()).extracting(ResolvedCommissionRule::getCommissionRuleId).containsExactly(1000L);
    }

    @Test
    void rejectsRulesWithSameSelectionOrderAndPriority() {
        Long contractId = 10L;
        Long policyVersionId = 100L;
        PaymentStage paymentStage = PaymentStage.GA_TO_FC;
        ResolvedCommissionPolicy policy = ResolvedCommissionPolicy.builder()
                .policyVersionId(policyVersionId)
                .build();
        ResolvedCommissionRule firstRule = ResolvedCommissionRule.builder()
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
}
