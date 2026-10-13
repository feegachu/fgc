package com.susukkang.fgc.arbitrage.service;

import com.susukkang.fgc.policy.repository.PolicyVersionRepository;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ArbitrageApplicabilityPolicyServiceTest {
    private final PolicyVersionRepository repository = mock(PolicyVersionRepository.class);
    private final ArbitrageApplicabilityPolicyService service = new ArbitrageApplicabilityPolicyService(repository);
    private final LocalDate asOf = LocalDate.of(2026, 8, 31);

    @Test
    void readsDateAndPolicyVersionWithoutHardcodedFallback() {
        var policy = policy("2027-01-01");
        when(repository.findArbitrageApplicability(asOf)).thenReturn(List.of(policy));
        var result = service.resolve(asOf);
        assertThat(result.effectiveContractDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(result.policyVersionId()).isEqualTo(99L);
        assertThat(result.reviewReason()).isNull();
    }

    @Test
    void missingAndDuplicatePoliciesRequireReview() {
        when(repository.findArbitrageApplicability(asOf)).thenReturn(List.of());
        assertThat(service.resolve(asOf).reviewReason()).contains("없습니다");
        var policy = policy("2026-03-01");
        when(repository.findArbitrageApplicability(asOf)).thenReturn(List.of(policy, policy));
        assertThat(service.resolve(asOf).reviewReason()).contains("여러 건");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={"invalid", "2026-02-30", "20260301", "{}"})
    void malformedAndMissingParametersNeverBecomeClear(String value) {
        var policy = policy(value);
        when(repository.findArbitrageApplicability(asOf)).thenReturn(List.of(policy));
        assertThat(service.resolve(asOf).reviewReason()).isNotNull();
        assertThat(service.resolve(asOf).effectiveContractDate()).isNull();
    }

    private PolicyVersionRepository.ArbitrageApplicability policy(String value) {
        var policy = mock(PolicyVersionRepository.ArbitrageApplicability.class);
        when(policy.getPolicyVersionId()).thenReturn(99L);
        when(policy.getEffectiveContractDate()).thenReturn(value);
        return policy;
    }
}
