package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.dto.ResponseContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.susukkang.fgc.contract.domain.ContractStatus.ACTIVE;
import static com.susukkang.fgc.contract.domain.PaymentCycleCode.MONTHLY;
import static com.susukkang.fgc.contract.domain.PaymentCycleCode.QUARTERLY;
import static com.susukkang.fgc.contract.domain.PremiumConversionRuleCode.QUARTERLY_DIV_3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
    private CapCheckService capCheckService;

    @InjectMocks
    private ContractService contractService;

    @Test
    @DisplayName("검색 조건에 해당하는 보험계약 목록을 반환한다")
    void selectByConditionReturnsContracts() {
        ContractSearchCondition condition = new ContractSearchCondition();
        ContractView contract = ContractView.builder()
                .contractNo("TEST-001")
                .build();
        given(contractMapper.selectByCondition(condition)).willReturn(List.of(contract));

        assertThat(contractService.selectByCondition(condition)).containsExactly(contract);
        verify(contractMapper).selectByCondition(condition);
    }

    @Test
    @DisplayName("분기납 계약을 생성할 때 초회보험료를 3으로 나누어 저장한다")
    void createContractCalculatesQuarterlyMonthlyEquivalent() {
        ContractCreateRequest request = createRequest();
        request.setPaymentCycleCode(QUARTERLY);
        request.setFirstPremiumAmount(new BigDecimal("300000"));
        givenValidReferences(request.getInsurerId(), request.getProductOfferingId(),
                request.getAgentId(), request.getOrganizationId());
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(false);
        given(contractMapper.insertContract(any(InsuranceContract.class))).willAnswer(invocation -> {
            InsuranceContract contract = invocation.getArgument(0);
            ReflectionTestUtils.setField(contract, "contractId", 21L);
            return 1;
        });

        ResponseContract response = contractService.createContract(request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).insertContract(captor.capture());
        InsuranceContract saved = captor.getValue();
        assertThat(saved.getMonthlyEquivalentFirstPremium()).isEqualByComparingTo("100000");
        assertThat(saved.getPremiumConversionRuleCode()).isEqualTo(QUARTERLY_DIV_3);
        assertThat(saved.getDataOrigin()).isEqualTo(DataOrigin.MANUAL);
        assertThat(response.getContractId()).isEqualTo(21L);
        verify(capCheckService).calculateAndSave(any());
    }

    @Test
    @DisplayName("중복 계약번호이면 계약 생성을 거절한다")
    void createContractRejectsDuplicateContractNumber() {
        ContractCreateRequest request = createRequest();
        givenValidReferences(request.getInsurerId(), request.getProductOfferingId(),
                request.getAgentId(), request.getOrganizationId());
        given(contractMapper.existsContractNo(request.getInsurerId(), request.getContractNo()))
                .willReturn(true);

        assertThatThrownBy(() -> contractService.createContract(request))
                .isInstanceOf(FgcBusinessException.class);
    }

    @Test
    @DisplayName("계약 수정 시 기존 계약번호와 데이터 출처를 유지한다")
    void updateContractKeepsContractNumberAndDataOrigin() {
        ContractUpdateRequest request = updateRequest();
        InsuranceContract current = InsuranceContract.builder()
                .contractId(21L)
                .contractNo("KEEP-001")
                .dataOrigin(DataOrigin.SEED)
                .build();
        given(contractMapper.selectById(21L)).willReturn(current);
        givenValidReferences(request.getInsurerId(), request.getProductOfferingId(),
                request.getAgentId(), request.getOrganizationId());
        given(contractMapper.updateContract(any(InsuranceContract.class))).willReturn(1);

        ResponseContract response = contractService.updateContract(21L, request);

        ArgumentCaptor<InsuranceContract> captor = ArgumentCaptor.forClass(InsuranceContract.class);
        verify(contractMapper).updateContract(captor.capture());
        assertThat(captor.getValue().getContractNo()).isEqualTo("KEEP-001");
        assertThat(captor.getValue().getDataOrigin()).isEqualTo(DataOrigin.SEED);
        assertThat(response.getContractId()).isEqualTo(21L);
    }

    @Test
    @DisplayName("존재하지 않는 계약은 수정할 수 없다")
    void updateContractRejectsMissingContract() {
        given(contractMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> contractService.updateContract(999L, updateRequest()))
                .isInstanceOf(FgcBusinessException.class);
    }

    private void givenValidReferences(Long insurerId, Long productOfferingId,
                                      Long agentId, Long organizationId) {
        given(contractMapper.existsInsurer(insurerId)).willReturn(true);
        given(contractMapper.existsProductOffering(insurerId, productOfferingId)).willReturn(true);
        given(contractMapper.existsAgent(agentId)).willReturn(true);
        given(contractMapper.existsAgentOrganization(agentId, organizationId)).willReturn(true);
    }

    private ContractCreateRequest createRequest() {
        return new ContractCreateRequest(
                1L, "TEST-001", 1L, LocalDate.now(), ACTIVE,
                1L, 4L, MONTHLY,
                new BigDecimal("100000"), new BigDecimal("100000"),
                new BigDecimal("100000"), 120, new BigDecimal("50000")
        );
    }

    private ContractUpdateRequest updateRequest() {
        return new ContractUpdateRequest(
                1L, 1L, LocalDate.now(), ACTIVE,
                1L, 4L, MONTHLY,
                new BigDecimal("100000"), new BigDecimal("100000"),
                120, new BigDecimal("50000")
        );
    }
}
