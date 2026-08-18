package com.susukkang.fgc.schedule.service;

import com.susukkang.fgc.base.mapper.AgentMapper;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
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
import com.susukkang.fgc.schedule.dto.ScheduleDetailResponse;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleRegenResponse;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    @Mock
    private CapCheckService capCheckService;

    @Mock
    private CapCheckMapper capCheckMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ScheduleReviewService scheduleReviewService;

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
        CapCalculationResult result = mock(CapCalculationResult.class);
        lenient().when(result.resultStatus()).thenReturn(CapResultStatus.NORMAL);
        lenient().when(capCheckService.calculateAndSave(any()))
                .thenReturn(new CapCheckSaveResult(1L, result));
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
    void preservesLockedLinesAndRecalculatesPlannedLinesWhenRegenerating() {
        ScheduleLineInsertDTO confirmedOldLine = ScheduleLineInsertDTO.builder().scheduleHeaderId(10L).lineNo(1).installmentNo(1).contractMonthNo(1).dueDate(LocalDate.of(2026, 8, 10)).commissionItemId(100L).beneficiaryAgentId(20L).basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM").basisAmount(new BigDecimal("100000")).calculationType(CalculationType.RATE).ratePct(new BigDecimal("100")).expectedAmount(new BigDecimal("100000")).roundingScale(0).roundingMode(RoundingMode.HALF_UP).lineStatus(ScheduleLineStatus.CONFIRMED).sourceCommissionRuleId(1000L).build();
        ScheduleLineInsertDTO recalculatedFirstLine = ScheduleLineInsertDTO.builder().scheduleHeaderId(20L).lineNo(1).installmentNo(1).contractMonthNo(1).dueDate(LocalDate.of(2026, 8, 10)).commissionItemId(100L).beneficiaryAgentId(20L).basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM").basisAmount(new BigDecimal("200000")).calculationType(CalculationType.RATE).ratePct(new BigDecimal("100")).expectedAmount(new BigDecimal("200000")).roundingScale(0).roundingMode(RoundingMode.HALF_UP).lineStatus(ScheduleLineStatus.PLANNED).sourceCommissionRuleId(2000L).build();
        ScheduleLineInsertDTO recalculatedFutureLine = ScheduleLineInsertDTO.builder().scheduleHeaderId(20L).lineNo(2).installmentNo(2).contractMonthNo(2).dueDate(LocalDate.of(2026, 9, 10)).commissionItemId(100L).beneficiaryAgentId(20L).basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM").basisAmount(new BigDecimal("200000")).calculationType(CalculationType.RATE).ratePct(new BigDecimal("100")).expectedAmount(new BigDecimal("200000")).roundingScale(0).roundingMode(RoundingMode.HALF_UP).lineStatus(ScheduleLineStatus.PLANNED).sourceCommissionRuleId(2000L).build();

        @SuppressWarnings("unchecked")
        List<ScheduleLineInsertDTO> mergedLines = ReflectionTestUtils.invokeMethod(scheduleService, "mergeLockedScheduleLines", List.of(confirmedOldLine), List.of(recalculatedFirstLine, recalculatedFutureLine), 30L);

        assertThat(mergedLines).hasSize(2);
        assertThat(mergedLines.get(0).getScheduleHeaderId()).isEqualTo(30L);
        assertThat(mergedLines.get(0).getExpectedAmount()).isEqualByComparingTo("100000");
        assertThat(mergedLines.get(0).getLineStatus()).isEqualTo(ScheduleLineStatus.CONFIRMED);
        assertThat(mergedLines.get(1).getExpectedAmount()).isEqualByComparingTo("200000");
        assertThat(mergedLines.get(1).getLineStatus()).isEqualTo(ScheduleLineStatus.PLANNED);
    }

    @Test
    void preservesLockedLineWithoutDuplicatingRecalculatedLineWhenBeneficiaryChanges() {
        ScheduleLineInsertDTO lockedLine = scheduleLine(100L, 1, 20L, ScheduleLineStatus.CONFIRMED, "100000");
        ScheduleLineInsertDTO changedBeneficiaryLine = scheduleLine(100L, 1, 30L, ScheduleLineStatus.PLANNED, "200000");

        @SuppressWarnings("unchecked")
        List<ScheduleLineInsertDTO> mergedLines = ReflectionTestUtils.invokeMethod(scheduleService, "mergeLockedScheduleLines", List.of(lockedLine), List.of(changedBeneficiaryLine), 30L);

        assertThat(mergedLines).hasSize(1);
        assertThat(mergedLines.getFirst().getBeneficiaryAgentId()).isEqualTo(20L);
        assertThat(mergedLines.getFirst().getExpectedAmount()).isEqualByComparingTo("100000");
        assertThat(mergedLines.getFirst().getLineStatus()).isEqualTo(ScheduleLineStatus.CONFIRMED);
    }

    @Test
    void preservesMultipleLockedBeneficiariesInSameItemAndInstallment() {
        ScheduleLineInsertDTO changedLockedLine = scheduleLine(100L, 1, 20L, ScheduleLineStatus.CONFIRMED, "100000");
        ScheduleLineInsertDTO unchangedLockedLine = scheduleLine(100L, 1, 40L, ScheduleLineStatus.CONFIRMED, "50000");
        ScheduleLineInsertDTO changedCalculatedLine = scheduleLine(100L, 1, 30L, ScheduleLineStatus.PLANNED, "200000");
        ScheduleLineInsertDTO unchangedCalculatedLine = scheduleLine(100L, 1, 40L, ScheduleLineStatus.PLANNED, "60000");

        @SuppressWarnings("unchecked")
        List<ScheduleLineInsertDTO> mergedLines = ReflectionTestUtils.invokeMethod(scheduleService, "mergeLockedScheduleLines", List.of(changedLockedLine, unchangedLockedLine), List.of(changedCalculatedLine, unchangedCalculatedLine), 30L);

        assertThat(mergedLines).hasSize(2);
        assertThat(mergedLines).extracting(ScheduleLineInsertDTO::getBeneficiaryAgentId).containsExactlyInAnyOrder(20L, 40L);
        assertThat(mergedLines).allMatch(line -> line.getLineStatus() == ScheduleLineStatus.CONFIRMED);
    }

    @Test
    void regeneratesScheduleAsNewVersionAndPreservesConfirmedLine() {
        ScheduleHeaderInsertDTO oldHeader = ScheduleHeaderInsertDTO.builder().scheduleHeaderId(10L).contractId(20L).paymentStage(PaymentStage.INSURER_TO_GA).policyVersionId(100L).scheduleVersionNo(1).status(ScheduleHeaderStatus.CONFIRMED).activeYn(true).build();
        InsuranceContract contract = InsuranceContract.builder().contractId(20L).contractDate(LocalDate.of(2026, 8, 10)).monthlyEquivalentFirstPremium(new BigDecimal("200000")).build();
        ResolvedCommissionRule currentRule = rule(1000L, null, 1, 2, "100.000000");
        ResolvedCommissionPolicy currentPolicy = policy(200L, PaymentStage.INSURER_TO_GA, currentRule);
        ScheduleLineInsertDTO confirmedLine = ScheduleLineInsertDTO.builder().scheduleHeaderId(10L).lineNo(1).installmentNo(1).contractMonthNo(1).dueDate(LocalDate.of(2026, 8, 10)).commissionItemId(1000L).basisCode("MONTHLY_EQUIVALENT_FIRST_PREMIUM").basisAmount(new BigDecimal("100000")).calculationType(CalculationType.RATE).ratePct(new BigDecimal("100")).expectedAmount(new BigDecimal("100000")).roundingScale(0).roundingMode(RoundingMode.HALF_UP).lineStatus(ScheduleLineStatus.CONFIRMED).sourceCommissionRuleId(1000L).build();

        given(scheduleMapper.selectScheduleHeaderById(10L)).willReturn(oldHeader);
        given(contractMapper.selectContractById(20L)).willReturn(contract);
        given(commissionPolicyService.resolveCurrentCommission(20L, PaymentStage.INSURER_TO_GA)).willReturn(currentPolicy);
        given(scheduleMapper.selectScheduleLinesByScheduleId(10L)).willReturn(List.of(confirmedLine));
        given(scheduleMapper.updateScheduleHeaderStatus(10L, ScheduleHeaderStatus.ADJUSTED, false)).willReturn(1);
        given(scheduleMapper.selectNextScheduleVersionNo(20L, PaymentStage.INSURER_TO_GA)).willReturn(2);
        given(scheduleMapper.insertScheduleHeader(any())).willAnswer(invocation -> {
            ScheduleHeaderInsertDTO insertedHeader = invocation.getArgument(0);
            ReflectionTestUtils.setField(insertedHeader, "scheduleHeaderId", 11L);
            return 1;
        });
        given(scheduleMapper.insertAllScheduleLines(any())).willAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        ScheduleRegenResponse response = scheduleService.regenerateSchedules(10L, "정책 변경 반영");

        assertThat(response.getScheduleHeaderId()).isEqualTo(11L);
        assertThat(response.getVersionNo()).isEqualTo(2L);
        verify(scheduleMapper).lockContractForScheduleGeneration(20L);
        verify(scheduleMapper, times(2)).selectScheduleHeaderById(10L);
        ArgumentCaptor<ScheduleHeaderInsertDTO> headerCaptor = ArgumentCaptor.forClass(ScheduleHeaderInsertDTO.class);
        verify(scheduleMapper).insertScheduleHeader(headerCaptor.capture());
        assertThat(headerCaptor.getValue().getRegeneratedFromId()).isEqualTo(10L);
        assertThat(headerCaptor.getValue().getGenerationReason()).isEqualTo("정책 변경 반영");
        assertThat(headerCaptor.getValue().getStatus()).isEqualTo(ScheduleHeaderStatus.PLANNED);
        assertThat(headerCaptor.getValue().getActiveYn()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ScheduleLineInsertDTO>> lineCaptor = ArgumentCaptor.forClass(List.class);
        verify(scheduleMapper).insertAllScheduleLines(lineCaptor.capture());
        assertThat(lineCaptor.getValue()).hasSize(2);
        assertThat(lineCaptor.getValue().get(0).getScheduleHeaderId()).isEqualTo(11L);
        assertThat(lineCaptor.getValue().get(0).getExpectedAmount()).isEqualByComparingTo("100000");
        assertThat(lineCaptor.getValue().get(0).getLineStatus()).isEqualTo(ScheduleLineStatus.CONFIRMED);
        assertThat(lineCaptor.getValue().get(1).getExpectedAmount()).isEqualByComparingTo("200000");
        assertThat(lineCaptor.getValue().get(1).getLineStatus()).isEqualTo(ScheduleLineStatus.PLANNED);
    }

    @Test
    void confirmsActivePlannedScheduleAndItsLines() {
        ScheduleHeaderInsertDTO plannedHeader = ScheduleHeaderInsertDTO.builder()
                .scheduleHeaderId(10L)
                .contractId(20L)
                .paymentStage(PaymentStage.GA_TO_FC)
                .policyVersionId(30L)
                .status(ScheduleHeaderStatus.PLANNED)
                .activeYn(true)
                .build();
        ScheduleDetailResponse confirmedDetail = ScheduleDetailResponse.builder()
                .header(ScheduleHeaderResponse.builder()
                        .scheduleHeaderId(10L)
                        .status(ScheduleHeaderStatus.CONFIRMED)
                        .build())
                .lines(List.of())
                .build();

        given(scheduleMapper.selectScheduleHeaderById(10L)).willReturn(plannedHeader);
        given(contractMapper.selectContractById(20L)).willReturn(InsuranceContract.builder()
                .contractId(20L).contractDate(LocalDate.of(2026, 8, 1)).build());
        given(scheduleMapper.confirmPlannedScheduleLines(10L)).willReturn(2);
        given(scheduleMapper.confirmScheduleHeader(10L)).willReturn(1);
        given(scheduleMapper.selectScheduleDetailById(10L)).willReturn(confirmedDetail);

        ScheduleDetailResponse response = scheduleService.confirmSchedule(10L);

        assertThat(response.getHeader().getStatus()).isEqualTo(ScheduleHeaderStatus.CONFIRMED);
        verify(scheduleMapper).lockContractForScheduleGeneration(20L);
        verify(scheduleMapper, times(2)).selectScheduleHeaderById(10L);
        verify(scheduleMapper).confirmPlannedScheduleLines(10L);
        verify(scheduleMapper).confirmScheduleHeader(10L);
    }

    @Test
    void confirmsAlreadyConfirmedScheduleIdempotently() {
        ScheduleHeaderInsertDTO confirmedHeader = ScheduleHeaderInsertDTO.builder()
                .scheduleHeaderId(10L)
                .contractId(20L)
                .status(ScheduleHeaderStatus.CONFIRMED)
                .activeYn(true)
                .build();
        ScheduleDetailResponse detail = ScheduleDetailResponse.builder()
                .header(ScheduleHeaderResponse.builder()
                        .scheduleHeaderId(10L)
                        .status(ScheduleHeaderStatus.CONFIRMED)
                        .build())
                .lines(List.of())
                .build();

        given(scheduleMapper.selectScheduleHeaderById(10L)).willReturn(confirmedHeader);
        given(scheduleMapper.selectScheduleDetailById(10L)).willReturn(detail);

        ScheduleDetailResponse response = scheduleService.confirmSchedule(10L);

        assertThat(response.getHeader().getStatus()).isEqualTo(ScheduleHeaderStatus.CONFIRMED);
        verify(scheduleMapper, never()).confirmPlannedScheduleLines(any());
        verify(scheduleMapper, never()).confirmScheduleHeader(any());
    }

    @Test
    void registersReviewAfterRollbackAndBlocksConfirmationWhenCapRuleIsMissing() {
        ScheduleHeaderInsertDTO plannedHeader = ScheduleHeaderInsertDTO.builder()
                .scheduleHeaderId(10L)
                .contractId(20L)
                .paymentStage(PaymentStage.GA_TO_FC)
                .status(ScheduleHeaderStatus.PLANNED)
                .activeYn(true)
                .build();
        given(scheduleMapper.selectScheduleHeaderById(10L)).willReturn(plannedHeader);
        given(contractMapper.selectContractById(20L)).willReturn(InsuranceContract.builder()
                .contractId(20L).contractDate(LocalDate.of(2026, 8, 1)).build());
        given(capCheckService.calculateAndSave(any())).willThrow(new FgcBusinessException(
                FgcErrorCode.CAP_004,
                "paymentStage",
                Map.of("contractId", 20L),
                "적용 가능한 1,200% 룰셋이 없습니다."
        ));

        assertThatThrownBy(() -> scheduleService.confirmSchedule(10L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.SCHE_004));

        verify(scheduleReviewService).registerCapRuleReviewAfterRollback(
                20L, PaymentStage.GA_TO_FC, "적용 가능한 1,200% 룰셋이 없습니다.");
        verify(scheduleMapper, never()).confirmPlannedScheduleLines(any());
        verify(scheduleMapper, never()).confirmScheduleHeader(any());
    }

    @Test
    void registersViolationReviewAndBlocksConfirmationWhenCapIsExceeded() {
        assertConfirmationBlockedWithCapReview(
                CapResultStatus.VIOLATION,
                FgcErrorCode.SCHE_003,
                "CAP_VIOLATION",
                "1,200% 한도 초과 - GA_TO_FC",
                "1,200% 한도 초과로 스케줄 확정을 차단했습니다."
        );
    }

    @Test
    void registersReviewRequiredCaseAndBlocksConfirmationWhenCapNeedsReview() {
        assertConfirmationBlockedWithCapReview(
                CapResultStatus.REVIEW_REQUIRED,
                FgcErrorCode.SCHE_004,
                "CAP_REVIEW_REQUIRED",
                "1,200% 한도 검토 필요 - GA_TO_FC",
                "한도 판정에 추가 검토가 필요해 스케줄 확정을 차단했습니다."
        );
    }

    @Test
    void registersReviewAndKeepsExistingHeaderWhenPolicyIsMissing() {
        assertRegenerationRegistersReview("POLICY_MISSING");
    }

    @Test
    void registersReviewAndKeepsExistingHeaderWhenPoliciesAreDuplicated() {
        assertRegenerationRegistersReview("POLICY_DUPLICATE");
    }

    @Test
    void rejectsInvalidRegenerationReasonBeforeAccessingDatabase() {
        assertThatThrownBy(() -> scheduleService.regenerateSchedules(10L, " ")).isInstanceOf(FgcBusinessException.class);
        assertThatThrownBy(() -> scheduleService.regenerateSchedules(10L, "가".repeat(41))).isInstanceOf(FgcBusinessException.class);
        verify(scheduleMapper, never()).selectScheduleHeaderById(any());
    }

    @Test
    void selectsOperationalSchedulesByContractId() {
        Long contractId = 10L;
        ScheduleDetailResponse detail = ScheduleDetailResponse.builder()
                .header(ScheduleHeaderResponse.builder().scheduleHeaderId(100L).build())
                .lines(List.of())
                .build();
        given(scheduleMapper.selectByContractIdAndPaymentStage(
                contractId, PaymentStage.INSURER_TO_GA))
                .willReturn(detail);

        var result = scheduleService.selectByContractId(
                contractId, PaymentStage.INSURER_TO_GA);

        assertThat(result.getHeaders()).hasSize(1);
        assertThat(result.getLines()).isEmpty();
        verify(scheduleMapper).selectByContractIdAndPaymentStage(
                contractId, PaymentStage.INSURER_TO_GA);
    }

    @Test
    void selectsAllVersionsForScheduleContractAndStage() {
        List<ScheduleHeaderResponse> versions = List.of(
                ScheduleHeaderResponse.builder().scheduleHeaderId(11L).scheduleVersionNo(2).build(),
                ScheduleHeaderResponse.builder().scheduleHeaderId(10L).scheduleVersionNo(1).build()
        );
        given(scheduleMapper.selectVersionsByScheduleHeaderId(10L)).willReturn(versions);

        assertThat(scheduleService.selectScheduleVersions(10L)).containsExactlyElementsOf(versions);
        verify(scheduleMapper).selectVersionsByScheduleHeaderId(10L);
    }

    @Test
    void generatesFixedRulesWithoutResolvingRateBasis() {
        InsuranceContract contract = InsuranceContract.builder()
                .contractId(10L)
                .contractDate(LocalDate.of(2026, 8, 10))
                .build();
        ResolvedCommissionRule fixedRule = ResolvedCommissionRule.builder()
                .commissionRuleId(1000L)
                .commissionItemId(1000L)
                .installmentFrom(1)
                .installmentTo(1)
                .basisCode("FIXED_AMOUNT")
                .calculationType(CalculationType.FIXED)
                .fixedAmount(new BigDecimal("25000"))
                .roundingScale(0)
                .roundingMode(RoundingMode.HALF_UP)
                .build();

        @SuppressWarnings("unchecked")
        List<ScheduleLineInsertDTO> lines = ReflectionTestUtils.invokeMethod(
                scheduleService,
                "createScheduleLines",
                contract,
                ScheduleHeaderInsertDTO.builder().scheduleHeaderId(100L).build(),
                policy(200L, PaymentStage.INSURER_TO_GA, fixedRule)
        );

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getBasisAmount()).isEqualByComparingTo("25000");
        assertThat(lines.getFirst().getExpectedAmount()).isEqualByComparingTo("25000");
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

        given(contractMapper.selectContractById(contract.getContractId())).willReturn(contract);
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

        given(contractMapper.selectContractById(contract.getContractId())).willReturn(contract);
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

        given(contractMapper.selectContractById(contract.getContractId())).willReturn(contract);
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

        given(contractMapper.selectContractById(contract.getContractId())).willReturn(contract);
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

    private void assertConfirmationBlockedWithCapReview(
            CapResultStatus resultStatus,
            FgcErrorCode expectedErrorCode,
            String exceptionType,
            String title,
            String description
    ) {
        ScheduleHeaderInsertDTO plannedHeader = ScheduleHeaderInsertDTO.builder()
                .scheduleHeaderId(10L)
                .contractId(20L)
                .paymentStage(PaymentStage.GA_TO_FC)
                .status(ScheduleHeaderStatus.PLANNED)
                .activeYn(true)
                .build();
        CapCalculationResult result = mock(CapCalculationResult.class);
        given(result.resultStatus()).willReturn(resultStatus);
        given(scheduleMapper.selectScheduleHeaderById(10L)).willReturn(plannedHeader);
        given(contractMapper.selectContractById(20L)).willReturn(InsuranceContract.builder()
                .contractId(20L).contractDate(LocalDate.of(2026, 8, 1)).build());
        given(capCheckService.calculateAndSave(any())).willReturn(new CapCheckSaveResult(1L, result));

        assertThatThrownBy(() -> scheduleService.confirmSchedule(10L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode));

        verify(scheduleReviewService).registerCapReviewAfterRollback(
                20L, PaymentStage.GA_TO_FC, exceptionType, title, description);
        verify(scheduleMapper, never()).confirmPlannedScheduleLines(any());
        verify(scheduleMapper, never()).confirmScheduleHeader(any());
    }

    private void assertRegenerationRegistersReview(String reason) {
        ScheduleHeaderInsertDTO oldHeader = ScheduleHeaderInsertDTO.builder().scheduleHeaderId(10L).contractId(20L).paymentStage(PaymentStage.INSURER_TO_GA).scheduleVersionNo(1).status(ScheduleHeaderStatus.CONFIRMED).activeYn(true).build();
        InsuranceContract contract = InsuranceContract.builder().contractId(20L).build();
        given(scheduleMapper.selectScheduleHeaderById(10L)).willReturn(oldHeader);
        given(contractMapper.selectContractById(20L)).willReturn(contract);
        given(commissionPolicyService.resolveCurrentCommission(20L, PaymentStage.INSURER_TO_GA)).willThrow(policyResolutionException(20L, PaymentStage.INSURER_TO_GA, reason));
        given(scheduleMapper.upsertPolicyReviewCase(any(), any(), any(), any(), any())).willReturn(1);

        ScheduleRegenResponse response = scheduleService.regenerateSchedules(10L, "정책 변경 반영");

        assertThat(response.getScheduleHeaderId()).isEqualTo(10L);
        assertThat(response.getVersionNo()).isEqualTo(1L);
        verify(scheduleMapper).upsertPolicyReviewCase(20L, PaymentStage.INSURER_TO_GA, reason, "수수료 정책 검토 필요 - INSURER_TO_GA", "예상 스케줄에 적용할 정책을 확정할 수 없습니다.");
        verify(scheduleMapper, never()).updateScheduleHeaderStatus(
                anyLong(), any(ScheduleHeaderStatus.class), anyBoolean());
        verify(scheduleMapper, never()).insertScheduleHeader(any());
        verify(scheduleMapper, never()).insertAllScheduleLines(any());
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

    private ScheduleLineInsertDTO scheduleLine(Long commissionItemId, int installmentNo, Long beneficiaryAgentId, ScheduleLineStatus status, String expectedAmount) {
        return ScheduleLineInsertDTO.builder().commissionItemId(commissionItemId).installmentNo(installmentNo).beneficiaryAgentId(beneficiaryAgentId).expectedAmount(new BigDecimal(expectedAmount)).lineStatus(status).build();
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
