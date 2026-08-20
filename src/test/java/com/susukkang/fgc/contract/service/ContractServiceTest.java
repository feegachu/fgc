package com.susukkang.fgc.contract.service;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.domain.ContractStatus;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.domain.PaymentCycleCode;
import com.susukkang.fgc.contract.domain.PremiumConversionRuleCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractInput;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.dto.ContractStatusEventProcessingRow;
import com.susukkang.fgc.contract.dto.ContractStatusEventRow;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.dto.ContractCreateResponse;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.contract.mapper.ContractStatusEventMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static com.susukkang.fgc.contract.domain.ContractStatus.ACTIVE;
import static com.susukkang.fgc.contract.domain.PaymentCycleCode.MONTHLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 보험계약 조회·생성·수정 비즈니스 로직 테스트
 * 입력값 검증, 월납환산보험료 계산 및 Mapper 호출 결과를 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
@ExtendWith(MockitoExtension.class)
class ContractServiceTest {

    @Mock
    private ContractMapper contractMapper;

    @Mock
    private ContractStatusEventMapper contractStatusEventMapper;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private CapCheckMapper capCheckMapper;

    @Mock
    private CapCheckService capCheckService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ContractService contractService;

    @Test
    @DisplayName("계약 상태 사건에 Job별 처리 이력을 묶고 미처리는 빈 배열로 반환한다")
    void selectStatusEventsGroupsProcessingsByEvent() {
        InsuranceContract contract = InsuranceContract.builder().contractId(21L).build();
        given(contractMapper.selectContractById(21L)).willReturn(contract);

        OffsetDateTime firstEffective = OffsetDateTime.parse("2026-07-10T00:00:00+09:00");
        OffsetDateTime secondEffective = OffsetDateTime.parse("2026-07-15T00:00:00+09:00");
        given(contractStatusEventMapper.selectByContractId(21L)).willReturn(List.of(
                new ContractStatusEventRow(101L, 1, null, ACTIVE, firstEffective,
                        firstEffective.plusDays(1), "INSURER_FEED", "EVENT-1"),
                new ContractStatusEventRow(102L, 2, ACTIVE,
                        com.susukkang.fgc.contract.domain.ContractStatus.TERMINATED,
                        secondEffective, secondEffective.plusDays(1), "INSURER_FEED", "EVENT-2")
        ));
        given(contractStatusEventMapper.selectProcessingsByContractId(21L)).willReturn(List.of(
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

        given(
                contractMapper.selectByCondition(
                        condition,
                        20,
                        0
                )
        ).willReturn(List.of(contract));

        given(
                contractMapper.countByCondition(condition)
        ).willReturn(1L);

        PageResponse<ContractView> response =
                contractService.selectByCondition(
                        condition,
                        1,
                        20
                );

        assertThat(response.content())
                .containsExactly(contract);

        assertThat(response.page())
                .isEqualTo(1);

        assertThat(response.size())
                .isEqualTo(20);

        assertThat(response.totalElements())
                .isEqualTo(1);

        assertThat(response.totalPages())
                .isEqualTo(1);

        verify(contractMapper).selectByCondition(
                condition,
                20,
                0
        );

        verify(contractMapper)
                .countByCondition(condition);
    }

    @Test
    @DisplayName("2페이지 조회 시 첫 20건을 건너뛴다")
    void selectByConditionCalculatesOffset() {
        ContractSearchCondition condition =
                new ContractSearchCondition();

        given(
                contractMapper.selectByCondition(
                        condition,
                        20,
                        20
                )
        ).willReturn(List.of());

        given(
                contractMapper.countByCondition(condition)
        ).willReturn(21L);

        PageResponse<ContractView> response =
                contractService.selectByCondition(
                        condition,
                        2,
                        20
                );

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.totalPages()).isEqualTo(2);

        verify(contractMapper).selectByCondition(
                condition,
                20,
                20
        );
    }

    @ParameterizedTest
    @MethodSource("premiumConversionCases")
    @DisplayName("지원 납입주기에서 화면의 주기 보험료를 그대로 저장한다")
    void createContractKeepsDirectPremiumByPaymentCycle(PaymentCycleCode paymentCycleCode) {
        ContractCreateRequest request = createRequest();
        request.setPaymentCycleCode(paymentCycleCode);
        request.setPremiumPerCycleAmount(new BigDecimal("275000"));
        givenValidReferences(request);
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(false);
        given(contractMapper.insertContract(any(InsuranceContract.class))).willAnswer(invocation -> {
            InsuranceContract contract = invocation.getArgument(0);
            ReflectionTestUtils.setField(contract, "contractId", 21L);
            return 1;
        });
        given(scheduleService.generateSchedules(any(InsuranceContract.class)))
                .willReturn(new ScheduleGenerationResult(List.of(100L, 101L), 3));
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.INSURER_TO_GA)).willReturn(true);
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.GA_TO_FC)).willReturn(true);

        ContractCreateResponse response = contractService.createContract(request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).insertContract(captor.capture());
        InsuranceContract saved = captor.getValue();
        assertThat(saved.getMonthlyEquivalentFirstPremium()).isEqualByComparingTo("100000");
        assertThat(saved.getPremiumPerCycleAmount())
                .isEqualByComparingTo("275000");
        assertThat(saved.getPremiumConversionRuleCode()).isEqualTo(PremiumConversionRuleCode.DIRECT_INPUT);
        assertThat(saved.getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
        assertThat(response.contractId()).isEqualTo(21L);
        assertThat(response.scheduleHeaderIds()).containsExactly(100L, 101L);
        verify(scheduleService).generateSchedules(saved);
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

        verify(contractMapper, never()).insertContract(any(InsuranceContract.class));
    }

    @Test
    @DisplayName("중복 계약번호이면 계약 생성을 거절한다")
    void createContractRejectsDuplicateContractNumber() {
        ContractCreateRequest request = createRequest();
        givenValidReferences(request);
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(true);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isInstanceOf(FgcBusinessException.class);
    }

    /*
     * 등록 직후 같은 트랜잭션에서 초회 재무 스냅샷·예상 스케줄(FUN-036)·1,200% 한도 검증(FUN-030)이
     * 연쇄 실행되므로, 이미 끝난 계약을 그대로 받으면 앞으로 받을 수수료를 새로 만들게 된다.
     * 화면에서 선택지를 줄이는 것만으로는 API 직접 호출을 막지 못해 서버에서도 막는다 (PR #315 리뷰).
     */
    @ParameterizedTest
    @EnumSource(
            value = ContractStatus.class,
            names = {"APPLIED", "ACTIVE"},
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("청약·정상이 아닌 계약상태로는 계약 생성을 거절한다")
    void createContractRejectsNonInitialContractStatus(ContractStatus contractStatus) {
        ContractCreateRequest request = createRequest();
        request.setContractStatus(contractStatus);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isInstanceOf(FgcBusinessException.class)
                .extracting(exception -> ((FgcBusinessException) exception).getField())
                .isEqualTo("contractStatus");

        verify(contractMapper, never()).insertContract(any(InsuranceContract.class));
        verify(scheduleService, never()).generateSchedules(any(InsuranceContract.class));
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"APPLIED", "ACTIVE"})
    @DisplayName("청약·정상 계약상태는 계약 생성을 통과한다")
    void createContractAcceptsInitialContractStatus(ContractStatus contractStatus) {
        ContractCreateRequest request = createRequest();
        request.setContractStatus(contractStatus);
        givenValidReferences(request);
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(false);
        given(contractMapper.insertContract(any(InsuranceContract.class))).willAnswer(invocation -> {
            InsuranceContract contract = invocation.getArgument(0);
            ReflectionTestUtils.setField(contract, "contractId", 21L);
            return 1;
        });
        given(scheduleService.generateSchedules(any(InsuranceContract.class)))
                .willReturn(new ScheduleGenerationResult(List.of(100L, 101L), 3));
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.INSURER_TO_GA)).willReturn(true);
        given(scheduleService.hasActiveOperationalSchedule(21L, PaymentStage.GA_TO_FC)).willReturn(true);

        contractService.createContract(request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).insertContract(captor.capture());
        assertThat(captor.getValue().getCurrentStatus()).isEqualTo(contractStatus);
    }

    @Test
    @DisplayName("계약 수정 시 계약번호를 변경하고 데이터 출처는 유지한다")
    void updateContractChangesContractNumberAndKeepsDataOrigin() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = InsuranceContract.builder()
                .contractId(21L)
                .contractNo("KEEP-001")
                .insurerId(1L)
                .dataOrigin(DataOrigin.SEED)
                .build();
        given(contractMapper.selectContractById(21L)).willReturn(current);
        givenValidReferences(request);
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(false);
        given(contractMapper.updateContract(any(InsuranceContract.class))).willReturn(1);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(200L, 201L));

        ContractUpdateResponse response = contractService.updateContract(21L, request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).updateContract(captor.capture());
        assertThat(captor.getValue().getContractNo()).isEqualTo("TEST-001");
        assertThat(captor.getValue().getDataOrigin()).isEqualTo(DataOrigin.SEED);
        assertThat(response.contractId()).isEqualTo(21L);
    }

    @Test
    @DisplayName("원수사와 계약번호가 같으면 중복 검사를 생략한다")
    void updateContractSkipsDuplicateCheckWhenIdentityIsUnchanged() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = InsuranceContract.builder()
                .contractId(21L)
                .contractNo(request.getContractNo())
                .insurerId(request.getInsurerId())
                .dataOrigin(DataOrigin.SEED)
                .build();
        given(contractMapper.selectContractById(21L)).willReturn(current);
        givenValidReferences(request);
        given(contractMapper.updateContract(any(InsuranceContract.class))).willReturn(1);
        given(scheduleService.regenerateContractSchedules(21L, "CONTRACT_UPDATED"))
                .willReturn(List.of(200L, 201L));

        contractService.updateContract(21L, request);

        verify(contractMapper, never()).existsContractNo(any(), any());
        verify(contractMapper).updateContract(any(InsuranceContract.class));
    }

    @Test
    @DisplayName("스케줄 산정 정보가 변경되면 스케줄 재생성과 한도 재검증을 수행한다")
    void updateContractRegeneratesSchedulesAndRechecksCapWhenScheduleInputChanges() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = InsuranceContract.builder()
                .contractId(21L)
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
                .build();
        given(contractMapper.selectContractById(21L)).willReturn(current);
        givenValidReferences(request);
        given(contractMapper.updateContract(any(InsuranceContract.class))).willReturn(1);
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
        verify(capCheckMapper).selectComplianceEvidenceAmount(21L, PaymentStage.INSURER_TO_GA);
        verify(capCheckMapper, never()).selectComplianceEvidenceAmount(21L, PaymentStage.GA_TO_FC);
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
        InsuranceContract current = InsuranceContract.builder()
                .contractId(21L)
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
                .build();
        given(contractMapper.selectContractById(21L)).willReturn(current);
        givenValidReferences(request);
        given(contractMapper.updateContract(any(InsuranceContract.class))).willReturn(1);
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
        InsuranceContract current = InsuranceContract.builder()
                .contractId(21L)
                .contractNo("OLD-001")
                .insurerId(request.getInsurerId())
                .dataOrigin(DataOrigin.SEED)
                .build();
        given(contractMapper.selectContractById(21L)).willReturn(current);
        givenValidReferences(request);
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(true);

        assertThatThrownBy(() -> contractService.updateContract(21L, request))
                .isInstanceOf(FgcBusinessException.class);

        verify(contractMapper, never()).updateContract(any(InsuranceContract.class));
    }

    @Test
    @DisplayName("존재하지 않는 계약은 수정할 수 없다")
    void updateContractRejectsMissingContract() {
        given(contractMapper.selectContractById(999L)).willReturn(null);

        assertThatThrownBy(() -> contractService.updateContract(999L, updateRequest()))
                .isInstanceOf(FgcBusinessException.class);
    }

    private void assertScheduleRegeneratedForRecipientChange(
            ContractUpdateRequest request,
            InsuranceContract current
    ) {
        given(contractMapper.selectContractById(21L)).willReturn(current);
        givenValidReferences(request);
        given(contractMapper.updateContract(any(InsuranceContract.class))).willReturn(1);
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
        return InsuranceContract.builder()
                .contractId(21L)
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
                .build();
    }

    private void givenValidReferences(ContractInput request) {
        given(contractMapper.existsInsurer(request.getInsurerId())).willReturn(true);
        given(contractMapper.existsProductOffering(
                request.getInsurerId(),
                request.getProductOfferingId(),
                request.getContractDate()
        )).willReturn(true);
        given(contractMapper.existsAgent(
                request.getAgentId(),
                request.getContractDate()
        )).willReturn(true);
        given(contractMapper.existsAgentOrganization(
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
