package com.susukkang.fgc.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapValidator;
import com.susukkang.fgc.cap.service.CapValidatorImpl;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;
import com.susukkang.fgc.transaction.domain.CommissionItemReference;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionRow;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentRow;
import com.susukkang.fgc.transaction.domain.ConfirmationData;
import com.susukkang.fgc.transaction.domain.ContractReference;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.mapper.CommissionPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 서비스 단위 테스트
 *
 * @author yslee
 * @since 2026-08-10
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
        CapValidator capValidator = new CapValidatorImpl();
        service = new CommissionPaymentServiceImpl(
                mapper,
                new ObjectMapper(),
                capValidator,
                capCalculator
        );
    }

    @Test
    void createsDraftWithMultipleAttributions() {
        stubReferences(3L, 9L);
        stubInsertAndResponse(List.of(attributionRow(1, 3L, "300000"), attributionRow(2, 9L, "200000")));

        CommissionPaymentCreateRequest request = createRequest(List.of(
                attribution(3L, "300000", AttributionMethod.APPROVED_ALLOCATION),
                attribution(9L, "200000", AttributionMethod.APPROVED_ALLOCATION)
        ));

        CommissionPaymentResponse response = service.create(request);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(response.attributions()).hasSize(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CommissionPaymentAttributionCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertAttributions(captor.capture());
        assertThat(captor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAttributionSequence)
                .containsExactly(1, 2);
        assertThat(captor.getValue())
                .extracting(CommissionPaymentAttributionCommand::getAmount)
                .containsExactly(new BigDecimal("300000"), new BigDecimal("200000"));
    }

    @Test
    void updateReplacesAttributionsOnlyWhileDraft() {
        stubReferences(3L, 9L);
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        )));
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 9L, "500000")));

        CommissionPaymentUpdateRequest request = updateRequest(List.of(
                attribution(9L, "500000", AttributionMethod.APPROVED_ALLOCATION)
        ));

        CommissionPaymentResponse response = service.update(101L, request);

        assertThat(response.attributions()).extracting(a -> a.contractId()).containsExactly(9L);
        verify(mapper).updateTransaction(any(CommissionPaymentCommand.class));
        verify(mapper).deleteAttributions(101L);
        verify(mapper).insertAttributions(anyList());
    }

    @Test
    void rejectsUpdateWhenPaymentIsNotDraft() {
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(withStatus(confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        ), CommissionPaymentStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.update(101L, updateRequest(List.of(
                attribution(3L, "500000", AttributionMethod.DIRECT)
        )))).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.TRAN_005));
    }

    @Test
    void confirmsEveryAttributionAfterFun033Validation() {
        List<ConfirmationData> data = List.of(
                confirmation(201L, 3L, "300000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.APPROVED_ALLOCATION, "EVIDENCE-1"),
                confirmation(202L, 9L, "200000", "500000", InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE, AttributionMethod.APPROVED_ALLOCATION, "EVIDENCE-2")
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(data);
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(mapper.findCapRuleSnapshot(101L, 202L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L), capCalculation(9L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(55L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L)).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(
                attributionRow(1, 3L, "300000"),
                attributionRow(2, 9L, "200000")
        ));

        CommissionPaymentResponse response = service.confirm(101L);

        assertThat(response.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        verify(mapper, org.mockito.Mockito.times(2)).insertCapCheck(any());
        verify(mapper, org.mockito.Mockito.times(2)).insertCapCheckDetail(any());
        verify(mapper).confirm(101L);
    }

    @Test
    void blocksReviewRequiredAttributionBeforeCapCalculation() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.REVIEW_REQUIRED,
                ExclusionType.NONE, AttributionMethod.MANUAL_REVIEW, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_002));

        verify(capCalculator, never()).calculate(any());
        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    @Test
    void blocksConfirmationAndCreatesExceptionWhenCapIsExceeded() {
        ConfirmationData data = confirmation(
                201L, 3L, "1300000", "1300000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(56L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_001));

        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    // 2026-08-10 yslee - 지급기준 스케줄이 이미 한도를 넘은 후보 지급 확정 차단
    // 기존 코드: CapCalculator가 VIOLATION을 반환해도 limitAmount만 사용하고 스케줄 산입액은 폐기
    // 문제: CONFIRMED 수기 지급이 없으면 스케줄 사용률 119%인 계약도 후보액만으로 NORMAL 판정
    // 개선: 스케줄 판정과 실제 지급 판정 중 엄격한 결과를 cap_check에 저장하고 FGC-CAP-001로 차단
    @Test
    void blocksConfirmationWhenScheduleCalculationAlreadyViolatesLimit() {
        ConfirmationData data = confirmation(
                201L, 3L, "10000", "10000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(
                3L,
                CapResultStatus.VIOLATION,
                Map.of(),
                new BigDecimal("1428000")
        ));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(59L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_001));

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getIncludedAmount()).isEqualByComparingTo("1428000");
        assertThat(captor.getValue().getUsagePct()).isEqualByComparingTo("119");
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    // 2026-08-10 yslee - 스케줄과 실제 지급을 같은 금액 흐름의 병렬 관점으로 결합
    // 기존 코드: 두 값을 함께 사용하지 않아 스케줄 판정이 누락됨
    // 문제: 단순 합산으로 보완하면 대사 전 동일 지급분을 두 번 산입하여 정상 지급도 차단할 수 있음
    // 개선: 스케줄 산입액과 기존 확정액+후보액 중 큰 값을 최종 산입액으로 사용
    @Test
    void usesStricterAmountWithoutDoubleCountingScheduleAndActualViews() {
        ConfirmationData data = confirmation(
                201L, 3L, "50000", "50000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("850000", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(
                3L,
                CapResultStatus.NORMAL,
                Map.of(),
                new BigDecimal("900000")
        ));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(60L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L)).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "50000")));

        service.confirm(101L);

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getIncludedAmount()).isEqualByComparingTo("900000");
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.NORMAL);
        assertThat(captor.getValue().getCalculationSnapshotJson())
                .contains("scheduledIncludedAmount", "existingIncludedAmount", "effectiveIncludedAmount");
        verify(mapper).confirm(101L);
    }

    @Test
    void passesVerifiedComplianceAmountToCalculator() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L))
                .willReturn(capRule("0", new BigDecimal("2500")));
        given(capCalculator.calculate(any())).willReturn(capCalculation(3L));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(57L);
            return null;
        }).when(mapper).insertCapCheck(any());
        given(mapper.confirm(101L)).willReturn(1);
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.CONFIRMED));
        given(mapper.findAttributions(101L)).willReturn(List.of(attributionRow(1, 3L, "500000")));

        service.confirm(101L);

        ArgumentCaptor<CapCalculationCommand> captor = ArgumentCaptor.forClass(CapCalculationCommand.class);
        verify(capCalculator).calculate(captor.capture());
        assertThat(captor.getValue().complianceEvidenceAmount()).isEqualByComparingTo("2500");
    }

    // 2026-08-10 yslee - 준법경영비 증빙 누락의 계산 근거를 확정 차단 이력으로 보존
    // 기존 코드: 계산기가 REVIEW_REQUIRED를 반환하면 cap_check 저장 전에 예외 발생
    // 문제: 증빙 누락 당시의 최대 허용액과 실제 적용액을 감사에서 재현할 수 없음
    // 개선: REVIEW_REQUIRED 점검 및 상세를 저장한 뒤 FGC-CAP-002로 확정을 차단
    @Test
    void persistsReviewRequiredSnapshotBeforeBlockingMissingComplianceEvidence() {
        ConfirmationData data = confirmation(
                201L, 3L, "500000", "500000", InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE, AttributionMethod.DIRECT, "EVIDENCE"
        );
        given(mapper.findConfirmationDataForUpdate(101L)).willReturn(List.of(data));
        given(mapper.findCapRuleSnapshot(101L, 201L)).willReturn(capRule("0", null));
        given(capCalculator.calculate(any())).willReturn(capCalculation(
                3L,
                CapResultStatus.REVIEW_REQUIRED,
                Map.of(
                        "complianceMaximumAmount", new BigDecimal("3000"),
                        "complianceAppliedAmount", BigDecimal.ZERO
                )
        ));
        doAnswer(invocation -> {
            invocation.<CapCheckCommand>getArgument(0).setCapCheckId(58L);
            return null;
        }).when(mapper).insertCapCheck(any());

        assertThatThrownBy(() -> service.confirm(101L))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.CAP_002));

        ArgumentCaptor<CapCheckCommand> captor = ArgumentCaptor.forClass(CapCheckCommand.class);
        verify(mapper).insertCapCheck(captor.capture());
        assertThat(captor.getValue().getResultStatus()).isEqualTo(CapResultStatus.REVIEW_REQUIRED);
        assertThat(captor.getValue().getCalculationSnapshotJson())
                .contains("complianceMaximumAmount", "3000", "complianceAppliedAmount");
        verify(mapper).insertCapCheckDetail(any());
        verify(mapper).insertExceptionCase(any());
        verify(mapper, never()).confirm(101L);
    }

    @Test
    void rejectsExcludedAttributionWithoutRegulatoryTypeOrEvidence() {
        stubReferences(3L);
        CommissionPaymentAttributionRequest invalid = new CommissionPaymentAttributionRequest(
                3L,
                new BigDecimal("500000"),
                InclusionDecisionStatus.EXCLUDED,
                ExclusionType.NONE,
                "제외",
                "DIRECT",
                null,
                AttributionMethod.DIRECT
        );

        assertThatThrownBy(() -> service.create(createRequest(List.of(invalid))))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.TRAN_004));
    }

    @Test
    void rejectsComplianceExclusionForGaToFcStage() {
        stubReferences(3L);
        CommissionPaymentAttributionRequest invalid = new CommissionPaymentAttributionRequest(
                3L,
                new BigDecimal("3000"),
                InclusionDecisionStatus.EXCLUDED,
                ExclusionType.COMPLIANCE_3PCT,
                "준법경영비",
                "DIRECT",
                "EVIDENCE",
                AttributionMethod.DIRECT
        );

        assertThatThrownBy(() -> service.create(createRequest(List.of(invalid))))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_002));
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

    private void stubReferences(Long... contractIds) {
        given(mapper.existsAgent(7L)).willReturn(true);
        given(mapper.findCommissionItem("BASE_COMMISSION", LocalDate.of(2026, 7, 1)))
                .willReturn(new CommissionItemReference(11L, "PAYMENT"));
        given(mapper.existsPolicyVersion(3L)).willReturn(true);
        lenient().when(mapper.findAllocationPolicyId(3L, "DIRECT")).thenReturn(77L);
        for (Long contractId : contractIds) {
            lenient().when(mapper.findContract(contractId))
                    .thenReturn(new ContractReference(contractId, 7L, LocalDate.of(2026, 7, 3)));
        }
    }

    private void stubInsertAndResponse(List<CommissionPaymentAttributionRow> attributions) {
        doAnswer(invocation -> {
            invocation.<CommissionPaymentCommand>getArgument(0).setPaymentId(101L);
            return null;
        }).when(mapper).insertTransaction(any());
        given(mapper.findById(101L)).willReturn(row(CommissionPaymentStatus.DRAFT));
        given(mapper.findAttributions(101L)).willReturn(attributions);
    }

    private CommissionPaymentCreateRequest createRequest(
            List<CommissionPaymentAttributionRequest> attributions
    ) {
        return new CommissionPaymentCreateRequest(
                "GA-2026-07-0001",
                1,
                3L,
                7L,
                "BASE_COMMISSION",
                attributions.stream()
                        .map(CommissionPaymentAttributionRequest::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                3L,
                attributions,
                "수기 등록"
        );
    }

    private CommissionPaymentUpdateRequest updateRequest(
            List<CommissionPaymentAttributionRequest> attributions
    ) {
        CommissionPaymentCreateRequest request = createRequest(attributions);
        return new CommissionPaymentUpdateRequest(
                request.paymentSequence(),
                request.contractId(),
                request.agentId(),
                request.commissionItemCode(),
                request.amount(),
                request.attributionMonth(),
                request.scheduledPaymentDate(),
                request.paymentStage(),
                request.allocationPolicyVersion(),
                request.attributions(),
                request.note()
        );
    }

    private CommissionPaymentAttributionRequest attribution(
            Long contractId,
            String amount,
            AttributionMethod method
    ) {
        return new CommissionPaymentAttributionRequest(
                contractId,
                new BigDecimal(amount),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "룰셋 산입",
                "DIRECT",
                "EVIDENCE",
                method
        );
    }

    private ConfirmationData confirmation(
            Long attributionId,
            Long contractId,
            String amount,
            String total,
            InclusionDecisionStatus status,
            ExclusionType exclusionType,
            AttributionMethod method,
            String evidenceRef
    ) {
        return new ConfirmationData(
                101L,
                CommissionPaymentStatus.DRAFT,
                new BigDecimal(total),
                new BigDecimal(amount),
                new BigDecimal(total),
                LocalDate.of(2026, 7, 1),
                attributionId,
                contractId,
                7L,
                PaymentStage.GA_TO_FC,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                3L,
                status,
                exclusionType,
                "룰셋 판정",
                "DIRECT",
                evidenceRef,
                method
        );
    }

    private CapRuleSnapshot capRule(String existingAmount, BigDecimal complianceEvidenceAmount) {
        return new CapRuleSnapshot(
                31L,
                41L,
                InclusionDecisionStatus.INCLUDED,
                "활성 룰셋 산입",
                new BigDecimal("100000"),
                new BigDecimal("12"),
                new BigDecimal("90"),
                new BigDecimal(existingAmount),
                complianceEvidenceAmount
        );
    }

    private ConfirmationData withStatus(ConfirmationData data, CommissionPaymentStatus status) {
        return new ConfirmationData(
                data.paymentId(),
                status,
                data.amount(),
                data.attributedAmount(),
                data.totalAttributedAmount(),
                data.attributionMonth(),
                data.transactionAttributionId(),
                data.contractId(),
                data.agentId(),
                data.paymentStage(),
                data.commissionItemId(),
                data.itemCode(),
                data.itemName(),
                data.policyVersionId(),
                data.inclusionDecisionStatus(),
                data.exclusionType(),
                data.inclusionDecisionReason(),
                data.allocationBasis(),
                data.evidenceRef(),
                data.attributionMethod()
        );
    }

    private CapCalculationResult capCalculation(Long contractId) {
        return capCalculation(contractId, CapResultStatus.NORMAL, Map.of());
    }

    private CapCalculationResult capCalculation(
            Long contractId,
            CapResultStatus resultStatus,
            Map<String, Object> snapshot
    ) {
        return capCalculation(contractId, resultStatus, snapshot, BigDecimal.ZERO);
    }

    private CapCalculationResult capCalculation(
            Long contractId,
            CapResultStatus resultStatus,
            Map<String, Object> snapshot,
            BigDecimal includedAmount
    ) {
        BigDecimal limitAmount = new BigDecimal("1200000");
        BigDecimal remainingAmount = limitAmount.subtract(includedAmount);
        BigDecimal usagePct = includedAmount.multiply(BigDecimal.valueOf(100))
                .divide(limitAmount, 6, RoundingMode.HALF_UP);
        return new CapCalculationResult(
                contractId,
                PaymentStage.GA_TO_FC,
                CapCheckKind.REALTIME,
                LocalDate.of(2026, 7, 1),
                31L,
                null,
                new BigDecimal("100000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                limitAmount,
                includedAmount,
                remainingAmount,
                usagePct,
                resultStatus,
                List.of(),
                snapshot
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
                "수기 등록",
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00")
        );
    }

    private CommissionPaymentAttributionRow attributionRow(int sequence, Long contractId, String amount) {
        return new CommissionPaymentAttributionRow(
                sequence,
                contractId,
                new BigDecimal(amount),
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "룰셋 산입",
                "DIRECT",
                "EVIDENCE",
                AttributionMethod.APPROVED_ALLOCATION
        );
    }
}
