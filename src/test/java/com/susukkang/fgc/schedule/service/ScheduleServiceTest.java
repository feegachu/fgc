package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.base.mapper.AgentMapper;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleLineInsertDTO;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleMapper scheduleMapper;

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private CommissionPolicyService commissionPolicyService;

    @Mock
    private AgentMapper agentMapper;

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    void generatesInsurerAndGaSchedulesFromResolvedPolicies() {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(10L)
                .contractNo("C-2026-001")
                .contractDate(LocalDate.of(2026, 8, 10))
                .agentId(20L)
                .organizationId(30L)
                .monthlyEquivalentFirstPremium(new BigDecimal("100000"))
                .build();

        ResolvedCommissionPolicy insurerPolicy = policy(
                100L,
                PaymentStage.INSURER_TO_GA,
                rule(1000L, null, 1, 2, "900.000000")
        );
        ResolvedCommissionPolicy gaPolicy = policy(
                200L,
                PaymentStage.GA_TO_FC,
                rule(2000L, AgentRankCode.FC, 1, 1, "650.000000")
        );

        given(contractMapper.selectById(contract.getContractId())).willReturn(contract);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.INSURER_TO_GA))
                .willReturn(insurerPolicy);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.GA_TO_FC))
                .willReturn(gaPolicy);

        AtomicLong headerSequence = new AtomicLong(1000L);
        given(scheduleMapper.insertScheduleHeader(any(ScheduleHeaderInsertDTO.class)))
                .willAnswer(invocation -> {
                    ScheduleHeaderInsertDTO header = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            header, "scheduleHeaderId", headerSequence.getAndIncrement());
                    return 1;
                });
        given(scheduleMapper.insertAllScheduleLines(any()))
                .willAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        int createdLineCount = scheduleService.generateSchedules(contract);

        assertThat(createdLineCount).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleLineInsertDTO>> linesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(scheduleMapper, org.mockito.Mockito.times(2))
                .insertAllScheduleLines(linesCaptor.capture());

        List<ScheduleLineInsertDTO> insurerLines = linesCaptor.getAllValues().get(0);
        assertThat(insurerLines).hasSize(2);
        assertThat(insurerLines)
                .extracting(ScheduleLineInsertDTO::getExpectedAmount)
                .containsExactly(new BigDecimal("900000"), new BigDecimal("900000"));
        assertThat(insurerLines)
                .extracting(ScheduleLineInsertDTO::getBeneficiaryAgentId)
                .containsOnlyNulls();
        assertThat(insurerLines)
                .extracting(ScheduleLineInsertDTO::getDueDate)
                .containsExactly(
                        LocalDate.of(2026, 8, 10),
                        LocalDate.of(2026, 9, 10)
                );

        List<ScheduleLineInsertDTO> gaLines = linesCaptor.getAllValues().get(1);
        assertThat(gaLines).hasSize(1);
        assertThat(gaLines.getFirst().getExpectedAmount())
                .isEqualByComparingTo("650000");
        assertThat(gaLines.getFirst().getBeneficiaryAgentId()).isEqualTo(20L);
        verify(agentMapper, never()).findActiveAgentIdFromOrganizationHierarchy(
                any(), any(), any());
    }

    private ResolvedCommissionPolicy policy(
            Long policyVersionId,
            PaymentStage paymentStage,
            ResolvedCommissionRule rule
    ) {
        return ResolvedCommissionPolicy.builder()
                .policyVersionId(policyVersionId)
                .policyType(PolicyType.CURRENT_COMMISSION)
                .paymentStage(paymentStage)
                .rules(List.of(rule))
                .build();
    }

    private ResolvedCommissionRule rule(
            Long commissionRuleId,
            AgentRankCode rankCode,
            int installmentFrom,
            int installmentTo,
            String ratePct
    ) {
        return ResolvedCommissionRule.builder()
                .commissionRuleId(commissionRuleId)
                .commissionItemId(commissionRuleId)
                .agentRankCode(rankCode)
                .installmentFrom(installmentFrom)
                .installmentTo(installmentTo)
                .basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM")
                .calculationType(CalculationType.RATE)
                .ratePct(new BigDecimal(ratePct))
                .roundingScale(0)
                .roundingMode(RoundingMode.HALF_UP)
                .build();
    }
}
