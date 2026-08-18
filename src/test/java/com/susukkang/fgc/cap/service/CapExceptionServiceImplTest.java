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
        assertThat(inserted.getExceptionKey())
                .isEqualTo("CAP_WARNING:77:COMMISSION_TRANSACTION:10:GA_TO_FC:3");
        assertThat(inserted.getExceptionType()).isEqualTo(ExceptionType.CAP_WARNING);
        assertThat(inserted.getSeverity()).isEqualTo(ExceptionSeverity.WARNING);
        assertThat(inserted.getValidationRunId()).isEqualTo(77L);
        // SRC-032 D-05 이후 설명은 관리자가 읽는 문장 — ID·스냅샷 JSON은 cap_check 원천이 보존
        assertThat(inserted.getTitle()).isEqualTo("1,200% 주의 — 잔여 한도 100원");
        assertThat(inserted.getDescription())
                .contains("지급단계 GA_TO_FC")
                .contains("한도액 1,000원")
                .contains("산입액 900원")
                .contains("사용률 90%")
                .doesNotContain("계산근거")
                .doesNotContain("{\"source\":\"test\"}");
        assertThat(inserted.getTitle()).doesNotContain("{\"source\":\"test\"}");
    }

    @Test
    void createsViolationWithFrozenExceptionKeyFormat() {
        ArgumentCaptor<CapExceptionInsertDTO> captor = ArgumentCaptor.forClass(CapExceptionInsertDTO.class);

        capExceptionService.createIfNecessary(command(CapResultStatus.VIOLATION, 10L, 3L, null));

        verify(capExceptionMapper).insertException(captor.capture());
        assertThat(captor.getValue().getExceptionKey())
                .isEqualTo("CAP_VIOLATION:null:COMMISSION_TRANSACTION:10:GA_TO_FC:3");
        assertThat(captor.getValue().getExceptionType()).isEqualTo(ExceptionType.CAP_VIOLATION);
        assertThat(captor.getValue().getSeverity()).isEqualTo(ExceptionSeverity.CRITICAL);
        assertThat(captor.getValue().getTitle()).isEqualTo("1,200% 한도 초과 — 사용률 110%");
        assertThat(captor.getValue().getDescription()).contains("초과액 100원");
    }

    @Test
    void createsDifferentNaturalKeysForDifferentPaymentOrValidationRun() {
        ArgumentCaptor<CapExceptionInsertDTO> captor = ArgumentCaptor.forClass(CapExceptionInsertDTO.class);

        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 10L, 3L, null));
        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 11L, 3L, null));
        capExceptionService.createIfNecessary(command(CapResultStatus.WARNING, 10L, 4L, 77L));

        verify(capExceptionMapper, org.mockito.Mockito.times(3)).insertException(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CapExceptionInsertDTO::getExceptionKey)
                .containsExactly(
                        "CAP_WARNING:null:COMMISSION_TRANSACTION:10:GA_TO_FC:3",
                        "CAP_WARNING:null:COMMISSION_TRANSACTION:11:GA_TO_FC:3",
                        "CAP_WARNING:77:COMMISSION_TRANSACTION:10:GA_TO_FC:4"
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
    void rejectsResolutionForStatusOutsideAllowedOpenStatuses() {
        CapExceptionResolveCommand command = resolveCommand();
        given(capExceptionMapper.selectExceptionForUpdate(5L))
                .willReturn(new CapExceptionStatusRow(5L, "REJECTED"));

        assertThatThrownBy(() -> capExceptionService.resolve(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("해결할 수 없는 예외 상태입니다.");
        verify(capExceptionMapper, never()).insertExceptionAction(command);
        verify(capExceptionMapper, never()).updateExceptionResolved(5L);
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
            Long capRuleSetId,
            Long validationRunId
    ) {
        return CapExceptionCreateCommand.builder()
                .paymentId(paymentId)
                .contractId(1L)
                .agentId(2L)
                .policyVersionId(30L)
                .validationRunId(validationRunId)
                .paymentStage(PaymentStage.GA_TO_FC)
                .asOfDate(LocalDate.of(2026, 8, 12))
                .capCheckId(6L)
                .capRuleSetId(capRuleSetId)
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
                .calculationSnapshot("{\"source\":\"test\"}")
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
