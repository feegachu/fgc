package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapExceptionCreateCommand;
import com.susukkang.fgc.cap.dto.CapExceptionInsertDTO;
import com.susukkang.fgc.cap.dto.CapExceptionResolveCommand;
import com.susukkang.fgc.cap.dto.CapExceptionStatusRow;
import com.susukkang.fgc.cap.mapper.CapExceptionMapper;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.code.ExceptionSeverity;
import com.susukkang.fgc.common.code.ExceptionType;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 설명 : FGC-FUN-034 한도 예외 생성·해결 서비스 단위 테스트
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@ExtendWith(MockitoExtension.class)
class CapExceptionServiceImplTest {

    @Mock
    private CapExceptionMapper capExceptionMapper;

    private CapExceptionServiceImpl capExceptionService;

    @BeforeEach
    void setUp() {
        capExceptionService = new CapExceptionServiceImpl(capExceptionMapper);
    }

    @Test
    void doesNotCreateExceptionForNormalOrReviewRequiredResult() {
        capExceptionService.createIfNecessary(command(CapResultStatus.NORMAL, 10L, 3L, null));
        capExceptionService.createIfNecessary(command(CapResultStatus.REVIEW_REQUIRED, 10L, 3L, null));

        verify(capExceptionMapper, never()).insertException(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createsWarningWithNaturalKeyAndBatchRunId() {
        ArgumentCaptor<CapExceptionInsertDTO> captor = ArgumentCaptor.forClass(CapExceptionInsertDTO.class);

        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 10L, 3L, 77L));

        verify(capExceptionMapper).insertException(captor.capture());
        CapExceptionInsertDTO inserted = captor.getValue();
        assertThat(inserted.getExceptionKey()).isEqualTo("CAP:10:CAP_CHECK:3");
        assertThat(inserted.getExceptionType()).isEqualTo(ExceptionType.CAP_WARNING);
        assertThat(inserted.getSeverity()).isEqualTo(ExceptionSeverity.WARNING);
        assertThat(inserted.getValidationRunId()).isEqualTo(77L);
    }

    @Test
    void createsViolationWithSameNaturalKeyForWarningEscalation() {
        ArgumentCaptor<CapExceptionInsertDTO> captor = ArgumentCaptor.forClass(CapExceptionInsertDTO.class);

        capExceptionService.createIfNecessary(command(CapResultStatus.VIOLATION, 10L, 3L, null));

        verify(capExceptionMapper).insertException(captor.capture());
        assertThat(captor.getValue().getExceptionKey()).isEqualTo("CAP:10:CAP_CHECK:3");
        assertThat(captor.getValue().getExceptionType()).isEqualTo(ExceptionType.CAP_VIOLATION);
        assertThat(captor.getValue().getSeverity()).isEqualTo(ExceptionSeverity.CRITICAL);
        assertThat(captor.getValue().getDescription()).contains("초과액=100");
    }

    @Test
    void createsDifferentNaturalKeysForDifferentPaymentOrPolicyVersion() {
        ArgumentCaptor<CapExceptionInsertDTO> captor = ArgumentCaptor.forClass(CapExceptionInsertDTO.class);

        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 10L, 3L, null));
        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 11L, 3L, null));
        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 10L, 4L, null));

        verify(capExceptionMapper, org.mockito.Mockito.times(3)).insertException(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CapExceptionInsertDTO::getExceptionKey)
                .containsExactly(
                        "CAP:10:CAP_CHECK:3",
                        "CAP:11:CAP_CHECK:3",
                        "CAP:10:CAP_CHECK:4"
                );
    }

    @Test
    void rejectsMissingCreateCommand() {
        assertThatThrownBy(() -> capExceptionService.createIfNecessary(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesExceptionWithRequiredAction() {
        CapExceptionResolveCommand command = resolveCommand();
        given(capExceptionMapper.selectExceptionForUpdate(5L))
                .willReturn(new CapExceptionStatusRow(5L, "NEW"));
        given(capExceptionMapper.insertExceptionAction(command)).willReturn(1);
        given(capExceptionMapper.updateExceptionResolved(5L)).willReturn(1);

        capExceptionService.resolve(command);

        verify(capExceptionMapper).insertExceptionAction(command);
        verify(capExceptionMapper).updateExceptionResolved(5L);
    }

    @Test
    void requiresResolutionActionCodeAndReason() {
        CapExceptionResolveCommand command = CapExceptionResolveCommand.builder()
                .exceptionCaseId(5L)
                .actionBy(1L)
                .build();

        assertThatThrownBy(() -> capExceptionService.resolve(command))
                .isInstanceOf(IllegalArgumentException.class);
        verify(capExceptionMapper, never()).selectExceptionForUpdate(5L);
    }

    @Test
    void checksUnresolvedViolationByPaymentId() {
        given(capExceptionMapper.existsUnresolvedViolation(10L)).willReturn(true);

        assertThat(capExceptionService.hasUnresolvedViolation(10L)).isTrue();
    }

    private CapExceptionCreateCommand command(
            CapResultStatus status,
            Long paymentId,
            Long policyVersionId,
            Long validationRunId
    ) {
        return CapExceptionCreateCommand.builder()
                .paymentId(paymentId)
                .contractId(1L)
                .agentId(2L)
                .policyVersionId(policyVersionId)
                .validationRunId(validationRunId)
                .paymentStage(PaymentStage.GA_TO_FC)
                .asOfDate(LocalDate.of(2026, 8, 12))
                .capRuleSetId(4L)
                .refundRateTableId(5L)
                .basePremiumAmount(new BigDecimal("100"))
                .refund12mAmount(BigDecimal.ZERO)
                .complianceDeductionAmount(BigDecimal.ZERO)
                .limitAmount(new BigDecimal("1000"))
                .includedAmount(status == CapResultStatus.VIOLATION
                        ? new BigDecimal("1100")
                        : new BigDecimal("900"))
                .remainingAmount(status == CapResultStatus.VIOLATION
                        ? new BigDecimal("-100")
                        : new BigDecimal("100"))
                .usagePct(status == CapResultStatus.VIOLATION
                        ? new BigDecimal("110")
                        : new BigDecimal("90"))
                .resultStatus(status)
                .build();
    }

    private CapExceptionResolveCommand resolveCommand() {
        return CapExceptionResolveCommand.builder()
                .exceptionCaseId(5L)
                .actionType(ExceptionActionType.REDUCE)
                .reason("지급액 감액 완료")
                .evidenceRef("EVIDENCE-1")
                .actionBy(1L)
                .build();
    }
}
