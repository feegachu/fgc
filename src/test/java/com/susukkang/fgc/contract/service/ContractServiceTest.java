package com.susukkang.fgc.contract.service;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.common.exception.FgcBusinessException;
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
import com.susukkang.fgc.contract.dto.ContractResponse;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.contract.mapper.ContractStatusEventMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.schedule.dto.ScheduleGenerationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private ContractService contractService;

    @Test
    @DisplayName("계약 상태 사건에 Job별 처리 이력을 묶고 미처리는 빈 배열로 반환한다")
    void selectStatusEventsGroupsProcessingsByEvent() {
        given(contractMapper.selectContractIdByBusinessKey(7L, "SHARED-001")).willReturn(21L);

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

        var response = contractService.selectStatusEventsByBusinessKey(7L, "SHARED-001");

        assertThat(response).hasSize(2);
        assertThat(response.getFirst().effectiveAt()).isEqualTo(firstEffective);
        assertThat(response.getFirst().processings()).isEmpty();
        assertThat(response.get(1).processings())
                .extracting(processing -> processing.processingStatus())
                .containsExactly("FAILED", "SUCCEEDED");
    }

    @Test
    @DisplayName("같은 계약번호도 보험회사 업무키에 따라 서로 다른 계약 이력을 조회한다")
    void selectStatusEventsDistinguishesSameContractNumberByInsurer() {
        given(contractMapper.selectContractIdByBusinessKey(7L, "SHARED-001")).willReturn(21L);
        given(contractMapper.selectContractIdByBusinessKey(8L, "SHARED-001")).willReturn(22L);
        given(contractStatusEventMapper.selectByContractId(21L)).willReturn(List.of());
        given(contractStatusEventMapper.selectByContractId(22L)).willReturn(List.of());

        assertThat(contractService.selectStatusEventsByBusinessKey(7L, "SHARED-001")).isEmpty();
        assertThat(contractService.selectStatusEventsByBusinessKey(8L, "SHARED-001")).isEmpty();

        verify(contractStatusEventMapper).selectByContractId(21L);
        verify(contractStatusEventMapper).selectByContractId(22L);
    }

    @Test
    @DisplayName("존재하지 않는 보험회사·계약번호 업무키는 검증 오류로 처리한다")
    void selectStatusEventsRejectsMissingBusinessKey() {
        given(contractMapper.selectContractIdByBusinessKey(999L, "UNKNOWN")).willReturn(null);

        assertThatThrownBy(() -> contractService.selectStatusEventsByBusinessKey(999L, "UNKNOWN"))
                .isInstanceOf(FgcBusinessException.class)
                .extracting("field", "detail")
                .containsExactly("contractNo", "존재하지 않는 보험계약입니다.");

        verify(contractStatusEventMapper, never()).selectByContractId(any());
        verify(contractStatusEventMapper, never()).selectProcessingsByContractId(any());
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
    @DisplayName("납입주기에 따라 주기별 보험료와 환산 코드를 계산한다")
    void createContractCalculatesPremiumByPaymentCycle(
            PaymentCycleCode paymentCycleCode,
            String expectedPremiumPerCycleAmount,
            PremiumConversionRuleCode expectedRuleCode
    ) {
        ContractCreateRequest request = createRequest();
        request.setPaymentCycleCode(paymentCycleCode);
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

        ContractResponse response = contractService.createContract(request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).insertContract(captor.capture());
        InsuranceContract saved = captor.getValue();
        assertThat(saved.getMonthlyEquivalentFirstPremium()).isEqualByComparingTo("100000");
        assertThat(saved.getPremiumPerCycleAmount())
                .isEqualByComparingTo(expectedPremiumPerCycleAmount);
        assertThat(saved.getPremiumConversionRuleCode()).isEqualTo(expectedRuleCode);
        assertThat(saved.getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
        assertThat(response.getContractId()).isEqualTo(21L);
        assertThat(response.getScheduleHeaderIds()).containsExactly(100L, 101L);
        verify(scheduleService).generateSchedules(saved);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentCycleCode.class,
            names = {"SINGLE", "OTHER"}
    )
    @DisplayName("역산을 지원하지 않는 납입주기는 계약 생성을 거절한다")
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

        ContractResponse response = contractService.updateContract(21L, request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).updateContract(captor.capture());
        assertThat(captor.getValue().getContractNo()).isEqualTo("TEST-001");
        assertThat(captor.getValue().getDataOrigin()).isEqualTo(DataOrigin.SEED);
        assertThat(response.getContractId()).isEqualTo(21L);
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

        contractService.updateContract(21L, request);

        verify(contractMapper, never()).existsContractNo(any(), any());
        verify(contractMapper).updateContract(any(InsuranceContract.class));
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

    private static Stream<Arguments> premiumConversionCases() {
        return Stream.of(
                Arguments.of(
                        PaymentCycleCode.MONTHLY,
                        "100000",
                        PremiumConversionRuleCode.MONTHLY_AS_IS
                ),
                Arguments.of(
                        PaymentCycleCode.QUARTERLY,
                        "300000",
                        PremiumConversionRuleCode.MONTHLY_TO_QUARTERLY_X3
                ),
                Arguments.of(
                        PaymentCycleCode.SEMI_ANNUAL,
                        "600000",
                        PremiumConversionRuleCode.MONTHLY_TO_SEMI_ANNUAL_X6
                ),
                Arguments.of(
                        PaymentCycleCode.ANNUAL,
                        "1200000",
                        PremiumConversionRuleCode.MONTHLY_TO_ANNUAL_X12
                )
        );
    }

    private ContractCreateRequest createRequest() {
        return new ContractCreateRequest(
                1L, "TEST-001", 1L, LocalDate.now(), ACTIVE,
                1L, 4L, MONTHLY,
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
                120,
                new BigDecimal("50000")
        );
    }
}
