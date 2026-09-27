package com.susukkang.fgc.contract.service;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.code.DataOrigin;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.code.PremiumConversionRuleCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractInput;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.dto.ContractCreateResponse;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.ContractFinancialSnapshotRepository;
import com.susukkang.fgc.contract.repository.ContractQueryRepository;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.susukkang.fgc.common.code.ContractStatus.ACTIVE;
import static com.susukkang.fgc.common.code.PaymentCycleCode.MONTHLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 설명 : 보험계약 조회·생성·수정 비즈니스 로직 테스트
 * 입력값 검증, 보험료 저장 및 Repository 호출 결과를 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock
    private InsuranceContractRepository insuranceContractRepository;

    @Mock
    private ContractFinancialSnapshotRepository contractFinancialSnapshotRepository;

    @Mock
    private ContractStatusEventRepository contractStatusEventRepository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private ContractQueryRepository contractQueryRepository;

    @Mock
    private CapCheckService capCheckService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ContractService contractService;

    @Test
    @DisplayName("계약 상태 사건에 Job별 처리 이력을 묶고 미처리는 빈 배열로 반환한다")
    void selectStatusEventsGroupsProcessingsByEvent() {
        given(insuranceContractRepository.existsById(21L)).willReturn(true);

        OffsetDateTime firstEffective = OffsetDateTime.parse("2026-07-10T00:00:00+09:00");
        OffsetDateTime secondEffective = OffsetDateTime.parse("2026-07-15T00:00:00+09:00");
        given(contractStatusEventRepository.findByContractIdOrderByEffectiveAtAscEventSeqAsc(21L)).willReturn(List.of(
                statusEvent(101L, 1, null, ACTIVE, firstEffective,
                        firstEffective.plusDays(1), "INSURER_FEED", "EVENT-1"),
                statusEvent(102L, 2, ACTIVE,
                        com.susukkang.fgc.common.code.ContractStatus.TERMINATED,
                        secondEffective, secondEffective.plusDays(1), "INSURER_FEED", "EVENT-2")
        ));
        given(contractQueryRepository.findStatusEventProcessingsByContractId(21L)).willReturn(List.of(
                new ContractStatusEventProcessingRow(102L, "DailyChangedContractJob", "FAILED",
                        secondEffective.plusDays(1).plusHours(1)),
                new ContractStatusEventProcessingRow(102L, "DailyChangedContractJob", "SUCCEEDED",
                        secondEffective.plusDays(1).plusHours(2))
        ));

        var response = contractService.selectStatusEventsByContractId(21L);

        assertThat(response).hasSize(2);
        assertThat(response.getFirst().effectiveAt()).isEqualTo(firstEffective);
        assertThat(response.getFirst().processings()).isEmpty();
        assertThat(response.get(1).processings())
                .extracting(processing -> processing.processingStatus())
                .containsExactly("FAILED", "SUCCEEDED");
    }

    @Test
    @DisplayName("검색 조건에 해당하는 보험계약 목록을 페이징하여 반환한다")
    void selectByConditionReturnsContracts() {
        ContractSearchCondition condition =
                new ContractSearchCondition();

        ContractView contract = ContractView.builder()
                .contractNo("TEST-001")
                .build();

        PageRequest pageable = PageRequest.of(0, 20, Sort.by("contractId").descending());
        given(contractQueryRepository.search(condition, pageable))
                .willReturn(new PageImpl<>(List.of(contract), pageable, 1));

        PageResponse<ContractView> response = contractService.selectByCondition(condition, 1, 20);

        assertThat(response.content()).containsExactly(contract);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        verify(contractQueryRepository).search(condition, pageable);
    }

    @Test
    @DisplayName("2페이지 조회 시 첫 20건을 건너뛴다")
    void selectByConditionCalculatesOffset() {
        ContractSearchCondition condition = new ContractSearchCondition();
        PageRequest pageable = PageRequest.of(1, 20, Sort.by("contractId").descending());
        given(contractQueryRepository.search(condition, pageable))
                .willReturn(new PageImpl<>(List.of(), pageable, 21));

        PageResponse<ContractView> response = contractService.selectByCondition(condition, 2, 20);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(pageable.getOffset()).isEqualTo(20);
        verify(contractQueryRepository).search(condition, pageable);
    }

    @ParameterizedTest
    @MethodSource("premiumConversionCases")
    @DisplayName("지원 납입주기에서 화면의 주기 보험료를 그대로 저장한다")
    void createContractKeepsDirectPremiumByPaymentCycle(PaymentCycleCode paymentCycleCode) {
        ContractCreateRequest request = createRequest();
        request.setPaymentCycleCode(paymentCycleCode);
        request.setPremiumPerCycleAmount(new BigDecimal("275000"));
        givenValidReferences(request);
        given(insuranceContractRepository.existsByInsurerIdAndContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(false);
        given(insuranceContractRepository.saveAndFlush(any())).willAnswer(invocation -> {
            InsuranceContract contract = invocation.getArgument(0);
            ReflectionTestUtils.setField(contract, "contractId", 21L);
            return contract;
        });
        given(scheduleService.generateSchedulesByContractId(21L))
                .willReturn(new ScheduleGenerationResult(List.of(100L, 101L), 3));
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.INSURER_TO_GA)).willReturn(true);
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.GA_TO_FC)).willReturn(true);

        OffsetDateTime beforeCreation = DateUtil.nowSeoul();
        ContractCreateResponse response = contractService.createContract(request);
        OffsetDateTime afterCreation = DateUtil.nowSeoul();

        ArgumentCaptor<InsuranceContract> captor =
                ArgumentCaptor.forClass(InsuranceContract.class);
        InOrder creationOrder = inOrder(
                insuranceContractRepository, contractFinancialSnapshotRepository,
                scheduleService, contractStatusEventRepository, auditLogService
        );
        creationOrder.verify(insuranceContractRepository).saveAndFlush(captor.capture());
        creationOrder.verify(contractFinancialSnapshotRepository).insertInitialIfAbsent(21L);
        creationOrder.verify(scheduleService).generateSchedulesByContractId(21L);
        var saved = captor.getValue();
        assertThat(saved.getMonthlyEquivalentFirstPremium()).isEqualByComparingTo("100000");
        assertThat(saved.getPremiumPerCycleAmount())
                .isEqualByComparingTo("275000");
        assertThat(saved.getPremiumConversionRuleCode()).isEqualTo(PremiumConversionRuleCode.DIRECT_INPUT);
        assertThat(saved.getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
        assertThat(response.contractId()).isEqualTo(21L);
        assertThat(response.scheduleHeaderIds()).containsExactly(100L, 101L);
        ArgumentCaptor<ContractStatusEvent> eventCaptor = ArgumentCaptor.forClass(ContractStatusEvent.class);
        creationOrder.verify(contractStatusEventRepository).saveAndFlush(eventCaptor.capture());
        ContractStatusEvent event = eventCaptor.getValue();
        assertThat(event.getContractId()).isEqualTo(21L);
        assertThat(event.getEventSeq()).isEqualTo(1);
        assertThat(event.getPreviousStatus()).isNull();
        assertThat(event.getNewStatus()).isEqualTo(request.getContractStatus());
        assertThat(event.getReasonCode()).isEqualTo("NEW_CONTRACT");
        assertThat(event.getSourceSystem()).isEqualTo("FGC_MANUAL");
        assertThat(event.getSourceEventKey()).isEqualTo("CONTRACT_CREATED:21");
        assertThat(event.getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
        assertThat(event.getEffectiveAt())
                .isEqualTo(request.getContractDate()
                        .atStartOfDay(DateUtil.SEOUL_ZONE)
                        .toOffsetDateTime());
        assertThat(event.getReceivedAt()).isBetween(beforeCreation, afterCreation);
        assertThat(event.getReceivedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        ArgumentCaptor<AuditLogService.AuditEvent> auditCaptor =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        creationOrder.verify(auditLogService).record(auditCaptor.capture());
        Map<?, ?> after = (Map<?, ?>) auditCaptor.getValue().after();
        assertThat(after.get("contractId")).isEqualTo(21L);
        assertThat(after.get("contractNo")).isEqualTo(saved.getContractNo());
        assertThat(after.get("dataOrigin")).isEqualTo(DataOrigin.MANUAL);
        ArgumentCaptor<CapCalculationCommand> capCaptor =
                ArgumentCaptor.forClass(CapCalculationCommand.class);
        verify(capCheckService, times(2)).calculateAndSave(capCaptor.capture());
        assertThat(capCaptor.getAllValues())
                .extracting(CapCalculationCommand::paymentStage)
                .containsExactly(PaymentStage.INSURER_TO_GA, PaymentStage.GA_TO_FC);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentCycleCode.class,
            names = {"OTHER"}
    )
    @DisplayName("화면에서 지원하지 않는 기타 납입주기는 계약 생성을 거절한다")
    void createContractRejectsUnsupportedPaymentCycle(PaymentCycleCode paymentCycleCode) {
        ContractCreateRequest request = createRequest();
        request.setPaymentCycleCode(paymentCycleCode);
        givenValidReferences(request);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isInstanceOf(FgcBusinessException.class);

        verify(insuranceContractRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("중복 계약번호이면 계약 생성을 거절한다")
    void createContractRejectsDuplicateContractNumber() {
        ContractCreateRequest request = createRequest();
        givenValidReferences(request);
        given(insuranceContractRepository.existsByInsurerIdAndContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(true);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isInstanceOf(FgcBusinessException.class);

        verify(insuranceContractRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("계약 저장 실패 시 후속 생성 작업을 수행하지 않고 예외를 전달한다")
    void createContractStopsWhenSavingFails() {
        ContractCreateRequest request = createRequest();
        givenValidReferences(request);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("contract insert failed");
        given(insuranceContractRepository.saveAndFlush(any())).willThrow(failure);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isSameAs(failure);

        verifyNoInteractions(
                contractFinancialSnapshotRepository, contractStatusEventRepository,
                scheduleService, capCheckService, auditLogService
        );
    }

    @Test
    @DisplayName("최초 상태사건 저장 실패 시 생성 감사 로그를 남기지 않고 예외를 전달한다")
    void createContractStopsWhenInitialStatusEventSavingFails() {
        ContractCreateRequest request = createRequest();
        givenValidReferences(request);
        given(insuranceContractRepository.saveAndFlush(any())).willAnswer(invocation -> {
            InsuranceContract contract = invocation.getArgument(0);
            ReflectionTestUtils.setField(contract, "contractId", 21L);
            return contract;
        });
        given(scheduleService.generateSchedulesByContractId(21L))
                .willReturn(new ScheduleGenerationResult(List.of(), 0));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("status event insert failed");
        given(contractStatusEventRepository.saveAndFlush(any())).willThrow(failure);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isSameAs(failure);

        verify(contractStatusEventRepository).saveAndFlush(any(ContractStatusEvent.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("계약 수정 시 계약번호를 변경하고 데이터 출처는 유지한다")
    void updateContractChangesContractNumberAndKeepsDataOrigin() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = withContractId(21L, InsuranceContract.builder()
                .contractNo("KEEP-001")
                .insurerId(1L)
                .dataOrigin(DataOrigin.SEED)
                .build());
        given(insuranceContractRepository.findById(21L)).willReturn(Optional.of(current));
        givenValidReferences(request);
        given(insuranceContractRepository.existsByInsurerIdAndContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(false);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(200L, 201L));

        ContractUpdateResponse response = contractService.updateContract(21L, request);

        InOrder updateOrder = inOrder(
                insuranceContractRepository, contractFinancialSnapshotRepository, scheduleService);
        updateOrder.verify(insuranceContractRepository).flush();
        updateOrder.verify(contractFinancialSnapshotRepository).insertInitialIfAbsent(21L);
        updateOrder.verify(scheduleService).regenerateContractSchedules(21L, "CONTRACT_UPDATED");
        assertThat(current.getContractNo()).isEqualTo("TEST-001");
        assertThat(current.getDataOrigin()).isEqualTo(DataOrigin.SEED);
        ArgumentCaptor<AuditLogService.AuditEvent> auditCaptor =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).record(auditCaptor.capture());
        Map<?, ?> before = (Map<?, ?>) auditCaptor.getValue().before();
        Map<?, ?> after = (Map<?, ?>) auditCaptor.getValue().after();
        assertThat(before.get("contractNo")).isEqualTo("KEEP-001");
        assertThat(after.get("contractNo")).isEqualTo("TEST-001");
        assertThat(before.get("dataOrigin")).isEqualTo(DataOrigin.SEED);
        assertThat(after.get("dataOrigin")).isEqualTo(DataOrigin.SEED);
        assertThat(response.contractId()).isEqualTo(21L);
    }

    @Test
    @DisplayName("원수사와 계약번호가 같으면 중복 검사를 생략한다")
    void updateContractSkipsDuplicateCheckWhenIdentityIsUnchanged() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = withContractId(21L, InsuranceContract.builder()
                .contractNo(request.getContractNo())
                .insurerId(request.getInsurerId())
                .dataOrigin(DataOrigin.SEED)
                .build());
        given(insuranceContractRepository.findById(21L)).willReturn(Optional.of(current));
        givenValidReferences(request);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(200L, 201L));

        contractService.updateContract(21L, request);

        verify(insuranceContractRepository, never()).existsByInsurerIdAndContractNo(any(), any());
        verify(insuranceContractRepository).flush();
    }

    @Test
    @DisplayName("스케줄 산정 정보가 변경되면 스케줄 재생성과 한도 재검증을 수행한다")
    void updateContractRegeneratesSchedulesAndRechecksCapWhenScheduleInputChanges() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = withContractId(21L, InsuranceContract.builder()
                .contractNo(request.getContractNo())
                .insurerId(request.getInsurerId())
                .productOfferingId(request.getProductOfferingId())
                .contractDate(request.getContractDate())
                .paymentCycleCode(request.getPaymentCycleCode())
                .firstPremiumAmount(new BigDecimal("90000"))
                .monthlyEquivalentFirstPremium(new BigDecimal("90000"))
                .paymentTermMonths(request.getPaymentTermMonths())
                .standardSurrenderDeductionAmount(request.getStandardSurrenderDeductionAmount())
                .dataOrigin(DataOrigin.SEED)
                .build());
        given(insuranceContractRepository.findById(21L)).willReturn(Optional.of(current));
        givenValidReferences(request);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(101L, 102L));
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.INSURER_TO_GA)).willReturn(true);
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.GA_TO_FC)).willReturn(true);

        ContractUpdateResponse response = contractService.updateContract(21L, request);

        assertThat(response.regeneratedScheduleIds()).containsExactly(101L, 102L);
        assertThat(response.scheduleHeaderIds()).containsExactly(101L, 102L);
        verify(scheduleService).regenerateContractSchedules(21L, "CONTRACT_UPDATED");
        ArgumentCaptor<CapCalculationCommand> capCaptor = ArgumentCaptor.forClass(CapCalculationCommand.class);
        verify(capCheckService, org.mockito.Mockito.times(2)).calculateAndSave(capCaptor.capture());
        assertThat(capCaptor.getAllValues())
                .extracting(CapCalculationCommand::paymentStage)
                .containsExactly(PaymentStage.INSURER_TO_GA, PaymentStage.GA_TO_FC);
        verify(contractQueryRepository).findComplianceEvidenceAmount(21L, PaymentStage.INSURER_TO_GA);
        verify(contractQueryRepository, never()).findComplianceEvidenceAmount(21L, PaymentStage.GA_TO_FC);
    }

    @Test
    @DisplayName("모집설계사가 변경되면 수령자를 다시 계산하도록 스케줄을 재생성한다")
    void updateContractRegeneratesSchedulesWhenAgentChanges() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = contractMatching(request, 99L, request.getOrganizationId());

        assertScheduleRegeneratedForRecipientChange(request, current);
    }

    @Test
    @DisplayName("조직이 변경되면 관리자 수령자를 다시 계산하도록 스케줄을 재생성한다")
    void updateContractRegeneratesSchedulesWhenOrganizationChanges() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = contractMatching(request, request.getAgentId(), 99L);

        assertScheduleRegeneratedForRecipientChange(request, current);
    }

    @Test
    @DisplayName("보험회사가 변경되면 적용 정책을 다시 계산하도록 스케줄을 재생성한다")
    void updateContractRegeneratesSchedulesWhenInsurerChanges() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = contractMatching(
                request,
                99L,
                request.getAgentId(),
                request.getOrganizationId()
        );

        assertScheduleRegeneratedForRecipientChange(request, current);
    }

    @Test
    void updateContractRegistersReviewWhenCapRuleIsMissing() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = withContractId(21L, InsuranceContract.builder()
                .contractNo(request.getContractNo())
                .insurerId(request.getInsurerId())
                .productOfferingId(request.getProductOfferingId())
                .contractDate(request.getContractDate())
                .paymentCycleCode(request.getPaymentCycleCode())
                .firstPremiumAmount(new BigDecimal("90000"))
                .monthlyEquivalentFirstPremium(new BigDecimal("90000"))
                .paymentTermMonths(request.getPaymentTermMonths())
                .standardSurrenderDeductionAmount(request.getStandardSurrenderDeductionAmount())
                .dataOrigin(DataOrigin.SEED)
                .build());
        given(insuranceContractRepository.findById(21L)).willReturn(Optional.of(current));
        givenValidReferences(request);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(101L));
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.INSURER_TO_GA)).willReturn(true);
        given(capCheckService.calculateAndSave(any(CapCalculationCommand.class)))
                .willThrow(new FgcBusinessException(FgcErrorCode.CAP_004));

        ContractUpdateResponse response = contractService.updateContract(21L, request);

        assertThat(response.contractId()).isEqualTo(21L);
        verify(scheduleService).registerCapRuleReview(
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.eq(PaymentStage.INSURER_TO_GA),
                anyString());
    }

    @Test
    @DisplayName("변경할 원수사와 계약번호 조합이 중복되면 수정을 거절한다")
    void updateContractRejectsDuplicateIdentity() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = withContractId(21L, InsuranceContract.builder()
                .contractNo("OLD-001")
                .insurerId(request.getInsurerId())
                .dataOrigin(DataOrigin.SEED)
                .build());
        given(insuranceContractRepository.findById(21L)).willReturn(Optional.of(current));
        givenValidReferences(request);
        given(insuranceContractRepository.existsByInsurerIdAndContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(true);

        assertThatThrownBy(() -> contractService.updateContract(21L, request))
                .isInstanceOf(FgcBusinessException.class);

        verify(insuranceContractRepository, never()).flush();
        assertThat(current.getContractNo()).isEqualTo("OLD-001");
    }

    @Test
    @DisplayName("존재하지 않는 계약은 수정할 수 없다")
    void updateContractRejectsMissingContract() {
        given(insuranceContractRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> contractService.updateContract(999L, updateRequest()))
                .isInstanceOf(FgcBusinessException.class);
    }

    private void assertScheduleRegeneratedForRecipientChange(
            ContractUpdateRequest request,
            InsuranceContract current
    ) {
        given(insuranceContractRepository.findById(21L)).willReturn(Optional.of(current));
        givenValidReferences(request);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(101L, 102L));

        ContractUpdateResponse response = contractService.updateContract(21L, request);

        assertThat(response.scheduleHeaderIds()).containsExactly(101L, 102L);
        assertThat(response.regeneratedScheduleIds()).containsExactly(101L, 102L);
        verify(scheduleService).regenerateContractSchedules(21L, "CONTRACT_UPDATED");
    }

    private InsuranceContract contractMatching(
            ContractUpdateRequest request,
            Long agentId,
            Long organizationId
    ) {
        return contractMatching(request, request.getInsurerId(), agentId, organizationId);
    }

    private InsuranceContract contractMatching(
            ContractUpdateRequest request,
            Long insurerId,
            Long agentId,
            Long organizationId
    ) {
        return withContractId(21L, InsuranceContract.builder()
                .contractNo(request.getContractNo())
                .insurerId(insurerId)
                .productOfferingId(request.getProductOfferingId())
                .contractDate(request.getContractDate())
                .currentStatus(request.getContractStatus())
                .agentId(agentId)
                .organizationId(organizationId)
                .paymentCycleCode(request.getPaymentCycleCode())
                .premiumPerCycleAmount(request.getPremiumPerCycleAmount())
                .firstPremiumAmount(request.getFirstPremiumAmount())
                .monthlyEquivalentFirstPremium(request.getMonthlyEquivalentFirstPremium())
                .paymentTermMonths(request.getPaymentTermMonths())
                .standardSurrenderDeductionAmount(request.getStandardSurrenderDeductionAmount())
                .dataOrigin(DataOrigin.SEED)
                .build());
    }

    private InsuranceContract withContractId(Long id, InsuranceContract contract) {
        ReflectionTestUtils.setField(contract, "contractId", id);
        return contract;
    }

    private ContractStatusEvent statusEvent(
            Long id, int eventSeq,
            com.susukkang.fgc.common.code.ContractStatus previousStatus,
            com.susukkang.fgc.common.code.ContractStatus newStatus,
            OffsetDateTime effectiveAt, OffsetDateTime receivedAt,
            String sourceSystem, String sourceEventKey
    ) {
        ContractStatusEvent event = ContractStatusEvent.builder()
                .contractId(21L)
                .eventSeq(eventSeq)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .effectiveAt(effectiveAt)
                .receivedAt(receivedAt)
                .sourceSystem(sourceSystem)
                .sourceEventKey(sourceEventKey)
                .build();
        ReflectionTestUtils.setField(event, "contractStatusEventId", id);
        return event;
    }

    private void givenValidReferences(ContractInput request) {
        given(contractQueryRepository.existsActiveInsurer(request.getInsurerId())).willReturn(true);
        given(contractQueryRepository.existsValidProductOffering(
                request.getInsurerId(),
                request.getProductOfferingId(),
                request.getContractDate()
        )).willReturn(true);
        given(contractQueryRepository.existsEligibleAgent(
                request.getAgentId(),
                request.getContractDate()
        )).willReturn(true);
        given(contractQueryRepository.existsValidAgentOrganization(
                request.getAgentId(),
                request.getOrganizationId(),
                request.getContractDate()
        )).willReturn(true);
    }

    private static Stream<PaymentCycleCode> premiumConversionCases() {
        return Stream.of(
                PaymentCycleCode.MONTHLY,
                PaymentCycleCode.QUARTERLY,
                PaymentCycleCode.SEMI_ANNUAL,
                PaymentCycleCode.ANNUAL,
                PaymentCycleCode.SINGLE
        );
    }

    private ContractCreateRequest createRequest() {
        return new ContractCreateRequest(
                1L, "TEST-001", 1L, LocalDate.now(), ACTIVE,
                1L, 4L, MONTHLY,
                new BigDecimal("100000"),
                new BigDecimal("100000"), new BigDecimal("100000"),
                120, new BigDecimal("50000")
        );
    }

    private ContractUpdateRequest updateRequest() {
        return new ContractUpdateRequest(
                "TEST-001",
                1L,
                1L,
                LocalDate.now(),
                ACTIVE,
                1L,
                4L,
                MONTHLY,
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                120,
                new BigDecimal("50000")
        );
    }
}
