package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
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
        assertThat(result.getRules()).containsExactly(rule);

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
}