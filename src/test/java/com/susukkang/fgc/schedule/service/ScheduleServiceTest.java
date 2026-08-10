package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.base.mapper.AgentMapper;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.dto.ResolvedCommissionRule;
import com.susukkang.fgc.policy.service.CommissionPolicyService;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import com.susukkang.fgc.schedule.dto.ScheduleLineInsertDTO;
import com.susukkang.fgc.schedule.dto.ScheduleSearchCondition;
import com.susukkang.fgc.schedule.code.SchedulePurpose;
import com.susukkang.fgc.schedule.mapper.ScheduleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
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

    @BeforeEach
    void setUpScheduleGenerationDefaults() {
        lenient().when(scheduleMapper.lockContractForScheduleGeneration(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(scheduleMapper.selectNextScheduleVersionNo(any(), any()))
                .thenReturn(1);
        lenient().when(scheduleMapper.selectActiveOperationalPolicyVersionId(any(), any()))
                .thenReturn(null);
    }

    @Test
    void defaultsSchedulePurposeToOperational() {
        ScheduleSearchCondition condition = new ScheduleSearchCondition();
        given(scheduleMapper.selectByCondition(condition, 20, 0L))
                .willReturn(List.of());
        given(scheduleMapper.countByCondition(condition)).willReturn(0L);

        scheduleService.selectByCondition(condition, 1, 20);

        assertThat(condition.getPurpose()).isEqualTo(SchedulePurpose.OPERATIONAL);
        verify(scheduleMapper).selectByCondition(condition, 20, 0L);
        verify(scheduleMapper).countByCondition(condition);
    }

    @Test
    void selectsOperationalSchedulesByContractId() {
        Long contractId = 10L;
        given(scheduleMapper.selectByContractId(contractId))
                .willReturn(List.of());

        List<?> result = scheduleService.selectByContractId(contractId);

        assertThat(result).isEmpty();
        verify(scheduleMapper).selectByContractId(contractId);
    }

    @Test
    void keepsLargePaginationOffsetAsLong() {
        ScheduleSearchCondition condition = new ScheduleSearchCondition();
        int page = 30_000_000;
        int size = 100;
        long expectedOffset = 2_999_999_900L;

        given(scheduleMapper.selectByCondition(condition, size, expectedOffset))
                .willReturn(List.of());
        given(scheduleMapper.countByCondition(condition)).willReturn(0L);

        scheduleService.selectByCondition(condition, page, size);

        verify(scheduleMapper).selectByCondition(condition, size, expectedOffset);
    }

    @Test
    void roundsEachCommissionLineAtHalfWonBeforeSumming() {
        ResolvedCommissionRule rule = rule(
                1000L,
                AgentRankCode.FC,
                1,
                2,
                "50.000000"
        );

        BigDecimal firstLine = ReflectionTestUtils.invokeMethod(
                scheduleService,
                "calculateExpectedAmount",
                BigDecimal.ONE,
                rule
        );
        BigDecimal secondLine = ReflectionTestUtils.invokeMethod(
                scheduleService,
                "calculateExpectedAmount",
                BigDecimal.ONE,
                rule
        );

        assertThat(firstLine).isEqualByComparingTo("1");
        assertThat(secondLine).isEqualByComparingTo("1");
        assertThat(firstLine.add(secondLine)).isEqualByComparingTo("2");
    }

    @Test
    void rejectsUnsupportedRoundingPolicy() {
        ResolvedCommissionRule rule = ResolvedCommissionRule.builder()
                .commissionRuleId(1000L)
                .calculationType(CalculationType.RATE)
                .ratePct(new BigDecimal("10"))
                .roundingScale(2)
                .roundingMode(RoundingMode.HALF_EVEN)
                .build();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                scheduleService,
                "calculateExpectedAmount",
                BigDecimal.ONE,
                rule
        )).isInstanceOf(FgcBusinessException.class);
    }

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

        ScheduleGenerationResult generationResult = scheduleService.generateSchedules(contract);

        assertThat(generationResult.createdLineCount()).isEqualTo(3);
        assertThat(generationResult.scheduleHeaderIds())
                .containsExactly(1000L, 1001L);

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

    @Test
    void rejectsOverlappingRulesForSameCommissionItemAndBeneficiary() {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(10L)
                .contractDate(LocalDate.of(2026, 8, 10))
                .agentId(20L)
                .organizationId(30L)
                .monthlyEquivalentFirstPremium(new BigDecimal("100000"))
                .build();

        ResolvedCommissionRule firstRule =
                rule(1000L, AgentRankCode.FC, 1, 12, "100.000000");
        ResolvedCommissionRule overlappingRule = ResolvedCommissionRule.builder()
                .commissionRuleId(1001L)
                .commissionItemId(1000L)
                .agentRankCode(AgentRankCode.FC)
                .installmentFrom(12)
                .installmentTo(24)
                .basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM")
                .calculationType(CalculationType.RATE)
                .ratePct(new BigDecimal("50.000000"))
                .roundingScale(0)
                .roundingMode(RoundingMode.HALF_UP)
                .build();
        ResolvedCommissionPolicy insurerPolicy = policy(
                100L,
                PaymentStage.INSURER_TO_GA,
                rule(3000L, null, 1, 1, "100.000000")
        );
        ResolvedCommissionPolicy gaPolicy = ResolvedCommissionPolicy.builder()
                .policyVersionId(200L)
                .policyType(PolicyType.CURRENT_COMMISSION)
                .paymentStage(PaymentStage.GA_TO_FC)
                .rules(List.of(firstRule, overlappingRule))
                .build();

        given(contractMapper.selectById(contract.getContractId())).willReturn(contract);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.INSURER_TO_GA))
                .willReturn(insurerPolicy);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.GA_TO_FC))
                .willReturn(gaPolicy);

        assertThatThrownBy(() -> scheduleService.generateSchedules(contract))
                .isInstanceOf(FgcBusinessException.class)
                .hasMessage("FGC-COMMON-002");

        verify(scheduleMapper, never()).insertScheduleHeader(any());
        verify(scheduleMapper, never()).insertAllScheduleLines(any());
    }

    @Test
    void registersReviewCasesAndSkipsSchedulesWhenPoliciesCannotBeResolved() {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(10L)
                .contractDate(LocalDate.of(2026, 8, 10))
                .build();

        given(contractMapper.selectById(contract.getContractId())).willReturn(contract);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.INSURER_TO_GA))
                .willThrow(policyResolutionException(
                        contract.getContractId(),
                        PaymentStage.INSURER_TO_GA,
                        "POLICY_MISSING"
                ));
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.GA_TO_FC))
                .willThrow(policyResolutionException(
                        contract.getContractId(),
                        PaymentStage.GA_TO_FC,
                        "POLICY_DUPLICATE"
                ));
        given(scheduleMapper.upsertPolicyReviewCase(
                any(), any(), any(), any(), any()))
                .willReturn(1);

        ScheduleGenerationResult generationResult = scheduleService.generateSchedules(contract);

        assertThat(generationResult.createdLineCount()).isZero();
        assertThat(generationResult.scheduleHeaderIds()).isEmpty();
        verify(scheduleMapper, times(2)).upsertPolicyReviewCase(
                any(), any(), any(), any(), any());
        verify(scheduleMapper, never()).insertScheduleHeader(any());
        verify(scheduleMapper, never()).insertAllScheduleLines(any());
    }

    @Test
    void skipsGenerationWhenSamePoliciesAreAlreadyActive() {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(10L)
                .contractDate(LocalDate.of(2026, 8, 10))
                .build();
        ResolvedCommissionPolicy insurerPolicy = policy(
                100L,
                PaymentStage.INSURER_TO_GA,
                rule(1000L, null, 1, 1, "100.000000")
        );
        ResolvedCommissionPolicy gaPolicy = policy(
                200L,
                PaymentStage.GA_TO_FC,
                rule(2000L, AgentRankCode.FC, 1, 1, "100.000000")
        );

        given(contractMapper.selectById(contract.getContractId())).willReturn(contract);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.INSURER_TO_GA))
                .willReturn(insurerPolicy);
        given(commissionPolicyService.resolveCurrentCommission(
                contract.getContractId(), PaymentStage.GA_TO_FC))
                .willReturn(gaPolicy);
        given(scheduleMapper.selectActiveOperationalPolicyVersionId(
                contract.getContractId(), PaymentStage.INSURER_TO_GA))
                .willReturn(100L);
        given(scheduleMapper.selectActiveOperationalPolicyVersionId(
                contract.getContractId(), PaymentStage.GA_TO_FC))
                .willReturn(200L);

        ScheduleGenerationResult generationResult = scheduleService.generateSchedules(contract);

        assertThat(generationResult.createdLineCount()).isZero();
        assertThat(generationResult.scheduleHeaderIds()).isEmpty();
        verify(scheduleMapper, never()).insertScheduleHeader(any());
        verify(scheduleMapper, never()).insertAllScheduleLines(any());
    }

    private FgcBusinessException policyResolutionException(
            Long contractId,
            PaymentStage paymentStage,
            String reason
    ) {
        return new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                "commissionPolicy",
                Map.of(
                        "contractId", contractId,
                        "paymentStage", paymentStage.name(),
                        "reason", reason
                ),
                "예상 스케줄에 적용할 정책을 확정할 수 없습니다."
        );
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
