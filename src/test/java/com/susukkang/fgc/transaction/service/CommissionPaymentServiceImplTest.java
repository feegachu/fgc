package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapValidatorImpl;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 설명 : 수수료 지급 건 서비스 단위 테스트
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class CommissionPaymentServiceImplTest {

    @Mock
    private CommissionPaymentMapper mapper;

    @Mock
    private CapCalculator capCalculator;

    private CommissionPaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommissionPaymentServiceImpl(
                mapper,
                new ObjectMapper(),
                new CapValidatorImpl(),
                capCalculator
        );
    }

    @Test
    void createsDraftPaymentAndAttribution() {
        stubReferences();
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(101L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        CommissionPaymentResponse response = service.create(createRequest());

        assertThat(response.paymentId()).isEqualTo(101L);
        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        verify(mapper).insertTransaction(any());
        verify(mapper).insertAttribution(any());
    }

    @Test
    void updatesDraftPaymentAndReplacesAttribution() {
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(confirmation(
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("500000"),
                3L
        ));
        stubReferences();
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        CommissionPaymentResponse response = service.update(101L, updateRequest());

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        verify(mapper).updateTransaction(any());
        verify(mapper).deleteAttributions(101L);
        verify(mapper).insertAttribution(any());
    }

    @Test
    void createsDraftBeforeFun033ValidationEvenWhenCandidateExceedsCap() {
        stubReferences();
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(106L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(106L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        CommissionPaymentCreateRequest request = createRequest(new BigDecimal("1300000"));

        CommissionPaymentResponse response = service.create(request);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        verify(mapper).insertTransaction(any());
        verify(mapper).insertAttribution(any());
        verifyNoInteractions(capCalculator);
    }

    @Test
    void preservesUnperformedNewcomerSupportWithoutContractAsDraft() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem("SETTLEMENT_SUPPORT", LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(22L, "PAYMENT"));
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(102L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(102L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                "GA-2026-07-0002",
                1,
                null,
                7L,
                "SETTLEMENT_SUPPORT",
                new BigDecimal("300000"),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                null,
                InclusionDecisionStatus.REVIEW_REQUIRED,
                "위촉 당월 무실적 선지급",
                null,
                null,
                "EVIDENCE-002",
                AttributionMethod.NEWCOMER_NON_CONTRACT,
                null
        );

        CommissionPaymentResponse response = service.create(request);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        verify(mapper).insertAttribution(any());
    }

    @Test
    void attributesSettlementSupportToNewContractInPaymentMonth() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem("SETTLEMENT_SUPPORT", LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(22L, "PAYMENT"));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 7, 20)));
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(103L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(103L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        service.create(new CommissionPaymentCreateRequest(
                "GA-2026-07-SUPPORT",
                1,
                null,
                7L,
                "SETTLEMENT_SUPPORT",
                new BigDecimal("300000"),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                3L,
                InclusionDecisionStatus.INCLUDED,
                "지급월 신계약 귀속",
                3L,
                "MONTHLY_PREMIUM",
                "EVIDENCE-SUPPORT",
                AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY,
                null
        ));

        ArgumentCaptor<CommissionPaymentCommand> captor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertAttribution(captor.capture());
        assertThat(captor.getValue().getAttributedContractId()).isEqualTo(3L);
        assertThat(captor.getValue().getSettlementMonth())
                .isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void carriesForwardSupportToAnyContractInFirstNewContractMonth() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem("SETTLEMENT_SUPPORT", LocalDate.of(2026, 9, 1)))
                .willReturn(new CommissionItemReference(22L, "PAYMENT"));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(9L))
                .willReturn(new ContractReference(9L, 7L, LocalDate.of(2026, 9, 18)));
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 9, 1)))
                .willReturn(0);
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(104L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(104L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        service.create(new CommissionPaymentCreateRequest(
                "GA-2026-07-CARRY",
                1,
                null,
                7L,
                "SETTLEMENT_SUPPORT",
                new BigDecimal("300000"),
                YearMonth.of(2026, 9),
                LocalDate.of(2026, 9, 25),
                PaymentStage.GA_TO_FC,
                9L,
                InclusionDecisionStatus.INCLUDED,
                "위촉 당월 무실적 선지급분 최초 신계약월 귀속",
                3L,
                "MONTHLY_PREMIUM",
                "EVIDENCE-CARRY",
                AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD,
                null
        ));

        verify(mapper).countContractsBeforeMonth(7L, LocalDate.of(2026, 9, 1));
        verify(mapper).insertAttribution(any());
    }

    @Test
    void updatesUnperformedAppointmentMonthAdvanceToFirstNewContractMonth() {
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(new ConfirmationData(
                101L,
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("300000"),
                new BigDecimal("300000"),
                LocalDate.of(2026, 7, 1),
                201L,
                null,
                7L,
                PaymentStage.GA_TO_FC,
                22L,
                "SETTLEMENT_SUPPORT",
                "정착지원금(경력)",
                null,
                InclusionDecisionStatus.REVIEW_REQUIRED,
                "위촉 당월 무실적 선지급",
                null,
                "EVIDENCE-CARRY"
        ));
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem("SETTLEMENT_SUPPORT", LocalDate.of(2026, 9, 1)))
                .willReturn(new CommissionItemReference(22L, "PAYMENT"));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(9L))
                .willReturn(new ContractReference(9L, 7L, LocalDate.of(2026, 9, 18)));
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 9, 1)))
                .willReturn(0);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        service.update(101L, new CommissionPaymentUpdateRequest(
                1,
                null,
                7L,
                "SETTLEMENT_SUPPORT",
                new BigDecimal("300000"),
                YearMonth.of(2026, 9),
                LocalDate.of(2026, 9, 25),
                PaymentStage.GA_TO_FC,
                9L,
                InclusionDecisionStatus.INCLUDED,
                "최초 신계약 모집월 이월 귀속",
                3L,
                "MONTHLY_PREMIUM",
                "EVIDENCE-CARRY",
                AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD,
                null
        ));

        ArgumentCaptor<CommissionPaymentCommand> captor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).updateTransaction(captor.capture());
        assertThat(captor.getValue().getPaymentId()).isEqualTo(101L);
        assertThat(captor.getValue().getAttributedContractId()).isEqualTo(9L);
        assertThat(captor.getValue().getInclusionDecisionStatus())
                .isEqualTo(InclusionDecisionStatus.INCLUDED);
        verify(mapper).deleteAttributions(101L);
        verify(mapper).insertAttribution(any());
    }

    @Test
    void storesAllocationPolicyBasisAndEvidenceSnapshot() {
        stubReferences();
        given(mapper.findAllocationPolicyId(3L, "MONTHLY_PREMIUM")).willReturn(77L);
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(105L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(105L)).willReturn(row(CommissionPaymentStatus.DRAFT));

        CommissionPaymentCreateRequest base = createRequest();
        service.create(new CommissionPaymentCreateRequest(
                "GA-2026-07-ALLOCATION",
                base.paymentSequence(),
                base.contractId(),
                base.agentId(),
                base.commissionItemCode(),
                base.amount(),
                base.attributionMonth(),
                base.scheduledPaymentDate(),
                base.paymentStage(),
                base.attributedContractId(),
                base.inclusionDecisionStatus(),
                base.inclusionDecisionReason(),
                3L,
                "MONTHLY_PREMIUM",
                "EVIDENCE-ALLOCATION",
                AttributionMethod.APPROVED_ALLOCATION,
                base.note()
        ));

        ArgumentCaptor<CommissionPaymentCommand> captor =
                ArgumentCaptor.forClass(CommissionPaymentCommand.class);
        verify(mapper).insertAttribution(captor.capture());
        CommissionPaymentCommand command = captor.getValue();
        assertThat(command.getPolicyVersionId()).isEqualTo(3L);
        assertThat(command.getAllocationPolicyId()).isEqualTo(77L);
        assertThat(command.getEvidenceRef()).isEqualTo("EVIDENCE-ALLOCATION");
        assertThat(command.getAllocationBasisJson())
                .contains("MONTHLY_PREMIUM");
    }

    @Test
    void rejectsUpdateWhenPaymentIsNotDraft() {
        given(mapper.findConfirmationDataForUpdate(101L))
                .willReturn(confirmation(CommissionPaymentStatus.CONFIRMED, new BigDecimal("500000"), 3L));

        assertThatThrownBy(() -> service.update(101L, updateRequest()))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.TRAN_005));
    }

    @Test
    void confirmsDraftAfterCapValidation() {
        ConfirmationData data = confirmation(
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("500000"),
                3L
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);
        given(mapper.findCapRuleSnapshot(101L)).willReturn(capRule("500000"));
        given(capCalculator.calculate(any())).willReturn(capCalculation());
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(55L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L)).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));

        CommissionPaymentResponse response = service.confirm(101L);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        verify(mapper).insertCapCheckDetail(any());
        verify(mapper).confirm(101L);
    }

    @Test
    void blocksConfirmationAndCreatesExceptionWhenCapIsExceeded() {
        ConfirmationData data = confirmation(
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("1300000"),
                3L
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);
        given(mapper.findCapRuleSnapshot(101L)).willReturn(capRule("0"));
        given(capCalculator.calculate(any())).willReturn(capCalculation());
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(56L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.CAP_001));

        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    @Test
    void blocksConfirmationAndCreatesExceptionWhenAllocationPolicyIsMissing() {
        ConfirmationData data = confirmation(
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("500000"),
                null
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.TRAN_004));

        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    @Test
    void blocksConfirmationAndCreatesExceptionWhenExclusionEvidenceIsMissing() {
        ConfirmationData data = new ConfirmationData(
                101L,
                CommissionPaymentStatus.DRAFT,
                new BigDecimal("500000"),
                new BigDecimal("500000"),
                LocalDate.of(2026, 7, 1),
                201L,
                3L,
                7L,
                PaymentStage.GA_TO_FC,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                3L,
                InclusionDecisionStatus.EXCLUDED,
                "규정상 제외",
                "DIRECT",
                null
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.TRAN_004));

        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    @Test
    void confirmMethodDefinesTransactionBoundary() throws NoSuchMethodException {
        Transactional transactional = CommissionPaymentServiceImpl.class
                .getMethod("confirm", Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.noRollbackFor())
                .containsExactly(CommissionPaymentConfirmationRejectedException.class);
    }

    @Test
    void rejectsCarryForwardWhenTargetIsNotFirstContract() {
        stubReferences();
        given(mapper.countContractsBeforeMonth(7L, LocalDate.of(2026, 7, 1)))
                .willReturn(1);
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                "GA-2026-07-0003",
                1,
                null,
                7L,
                "BASE_COMMISSION",
                new BigDecimal("500000"),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                3L,
                InclusionDecisionStatus.INCLUDED,
                "최초 신계약월 이월 귀속",
                3L,
                "DIRECT",
                "EVIDENCE-003",
                AttributionMethod.FIRST_CONTRACT_CARRY_FORWARD,
                null
        );

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.COMMON_002));
    }

    private void stubReferences() {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem("BASE_COMMISSION", LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "PAYMENT"));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        given(mapper.findContract(3L))
                .willReturn(new ContractReference(3L, 7L, LocalDate.of(2026, 7, 3)));
    }

    private CommissionPaymentCreateRequest createRequest() {
        return createRequest(new BigDecimal("500000"));
    }

    private CommissionPaymentCreateRequest createRequest(BigDecimal amount) {
        return new CommissionPaymentCreateRequest(
                "GA-2026-07-0001",
                1,
                3L,
                7L,
                "BASE_COMMISSION",
                amount,
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                3L,
                InclusionDecisionStatus.INCLUDED,
                "룰셋 산입",
                3L,
                "DIRECT",
                "EVIDENCE-001",
                AttributionMethod.DIRECT,
                "수기 등록"
        );
    }

    private CommissionPaymentUpdateRequest updateRequest() {
        CommissionPaymentCreateRequest request = createRequest();
        return new CommissionPaymentUpdateRequest(
                request.paymentSequence(),
                request.contractId(),
                request.agentId(),
                request.commissionItemCode(),
                request.amount(),
                request.attributionMonth(),
                request.scheduledPaymentDate(),
                request.paymentStage(),
                request.attributedContractId(),
                request.inclusionDecisionStatus(),
                request.inclusionDecisionReason(),
                request.allocationPolicyVersion(),
                request.allocationBasis(),
                request.evidenceRef(),
                request.attributionMethod(),
                request.note()
        );
    }

    private ConfirmationData confirmation(
            CommissionPaymentStatus status,
            BigDecimal amount,
            Long policyVersionId
    ) {
        return new ConfirmationData(
                101L,
                status,
                amount,
                amount,
                LocalDate.of(2026, 7, 1),
                201L,
                3L,
                7L,
                PaymentStage.GA_TO_FC,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                policyVersionId,
                InclusionDecisionStatus.INCLUDED,
                "룰셋 산입",
                policyVersionId == null ? null : "DIRECT",
                "EVIDENCE-001"
        );
    }

    private CapRuleSnapshot capRule(String existingAmount) {
        return new CapRuleSnapshot(
                31L,
                41L,
                InclusionDecisionStatus.INCLUDED,
                "활성 룰셋 산입",
                new BigDecimal("100000"),
                new BigDecimal("12"),
                new BigDecimal("90"),
                new BigDecimal(existingAmount)
        );
    }

    private CapCalculationResult capCalculation() {
        return new CapCalculationResult(
                3L,
                PaymentStage.GA_TO_FC,
                CapCheckKind.REALTIME,
                LocalDate.of(2026, 7, 1),
                31L,
                null,
                new BigDecimal("1200000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1200000"),
                BigDecimal.ZERO,
                new BigDecimal("1200000"),
                BigDecimal.ZERO,
                CapResultStatus.NORMAL,
                List.of(),
                Map.of()
        );
    }

    private CommissionPaymentRow row(CommissionPaymentStatus status) {
        return new CommissionPaymentRow(
                101L,
                "GA-2026-07-0001",
                1,
                3L,
                7L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                new BigDecimal("500000"),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                status,
                3L,
                InclusionDecisionStatus.INCLUDED,
                "룰셋 산입",
                3L,
                "DIRECT",
                "EVIDENCE-001",
                AttributionMethod.DIRECT,
                "수기 등록",
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00")
        );
    }
}
