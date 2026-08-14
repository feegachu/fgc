package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.mapper.CapCheckMapper;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.mapper.ValidationTargetSelectionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CapCheckBatchAdapterTest {

    @Mock ValidationTargetSelectionMapper validationMapper;
    @Mock CapCheckMapper capCheckMapper;
    @Mock CapCheckBatchItemService itemService;
    @Mock CapCheckFailureRecordService failureRecordService;

    @Test
    void processesEverySelectedContractWithPartitionStageAndMonthEnd() {
        given(validationMapper.selectSelectedContractIds(118L))
                .willReturn(List.of(10L, 20L));
        given(capCheckMapper.existsApplicableRuleSet(10L, PaymentStage.GA_TO_FC))
                .willReturn(true);
        given(capCheckMapper.existsApplicableRuleSet(20L, PaymentStage.GA_TO_FC))
                .willReturn(true);
        given(capCheckMapper.selectComplianceEvidenceAmount(10L, PaymentStage.GA_TO_FC))
                .willReturn(new BigDecimal("30000"));
        given(capCheckMapper.selectComplianceEvidenceAmount(20L, PaymentStage.GA_TO_FC))
                .willReturn(new BigDecimal("50000"));
        CapCheckBatchAdapter adapter = new CapCheckBatchAdapter(
                validationMapper, capCheckMapper, itemService, failureRecordService);

        StepProcessingResult result = adapter.check(
                context(118L, LocalDate.of(2026, 8, 1)),
                PaymentStage.GA_TO_FC);

        ArgumentCaptor<CapCalculationCommand> captor =
                ArgumentCaptor.forClass(CapCalculationCommand.class);
        verify(itemService, times(2)).process(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CapCalculationCommand::contractId)
                .containsExactly(10L, 20L);
        assertThat(captor.getAllValues())
                .extracting(CapCalculationCommand::complianceEvidenceAmount)
                .containsExactly(new BigDecimal("30000"), new BigDecimal("50000"));
        assertThat(captor.getAllValues()).allSatisfy(command -> {
            assertThat(command.paymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
            assertThat(command.asOfDate()).isEqualTo(LocalDate.of(2026, 8, 31));
            assertThat(command.checkKind()).isEqualTo(CapCheckKind.MONTHLY);
            assertThat(command.validationRunId()).isEqualTo(118L);
        });
        assertThat(result.processedCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.skips()).isEmpty();
    }

    @Test
    void skipsOnlyContractThatRaisesBusinessException() {
        given(validationMapper.selectSelectedContractIds(118L))
                .willReturn(List.of(10L, 20L));
        given(capCheckMapper.existsApplicableRuleSet(10L, PaymentStage.INSURER_TO_GA))
                .willReturn(true);
        given(capCheckMapper.existsApplicableRuleSet(20L, PaymentStage.INSURER_TO_GA))
                .willReturn(true);
        doThrow(new FgcBusinessException(
                FgcErrorCode.COMMON_002,
                "contractId",
                Map.of("contractId", 10L),
                "검증 데이터가 부족합니다."))
                .when(itemService).process(argThat(command -> command.contractId().equals(10L)));
        CapCheckBatchAdapter adapter = new CapCheckBatchAdapter(
                validationMapper, capCheckMapper, itemService, failureRecordService);

        StepProcessingResult result = adapter.check(
                context(118L, LocalDate.of(2026, 8, 1)),
                PaymentStage.INSURER_TO_GA);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();
        assertThat(result.skips()).singleElement().satisfies(skip -> {
            assertThat(skip.contractId()).isEqualTo(10L);
            assertThat(skip.reasonCode()).isEqualTo("CAP_CHECK_FAILED");
        });
        verify(failureRecordService).record(
                118L,
                10L,
                PaymentStage.INSURER_TO_GA,
                "FGC-COMMON-002"
        );
        verify(itemService).process(argThat(command -> command.contractId().equals(20L)));
    }

    @Test
    void propagatesUnexpectedSystemFailure() {
        given(validationMapper.selectSelectedContractIds(118L))
                .willReturn(List.of(10L));
        given(capCheckMapper.existsApplicableRuleSet(10L, PaymentStage.GA_TO_FC))
                .willReturn(true);
        doThrow(new IllegalStateException("database unavailable"))
                .when(itemService).process(argThat(command -> command.contractId().equals(10L)));
        CapCheckBatchAdapter adapter = new CapCheckBatchAdapter(
                validationMapper, capCheckMapper, itemService, failureRecordService);

        assertThatThrownBy(() -> adapter.check(
                context(118L, LocalDate.of(2026, 8, 1)),
                PaymentStage.GA_TO_FC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void ignoresContractWhenNoRuleAppliesToPaymentStage() {
        given(validationMapper.selectSelectedContractIds(118L))
                .willReturn(List.of(10L));
        given(capCheckMapper.existsApplicableRuleSet(10L, PaymentStage.GA_TO_FC))
                .willReturn(false);
        CapCheckBatchAdapter adapter = new CapCheckBatchAdapter(
                validationMapper, capCheckMapper, itemService, failureRecordService);

        StepProcessingResult result = adapter.check(
                context(118L, LocalDate.of(2026, 8, 1)),
                PaymentStage.GA_TO_FC);

        assertThat(result.processedCount()).isZero();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.skips()).isEmpty();
        verify(capCheckMapper, never()).selectComplianceEvidenceAmount(
                10L, PaymentStage.GA_TO_FC);
        verify(itemService, never()).process(argThat(command -> command.contractId().equals(10L)));
        verify(failureRecordService, never()).record(
                118L, 10L, PaymentStage.GA_TO_FC, "FGC-COMMON-500");
    }

    private ValidationStepContext context(Long validationRunId, LocalDate validationMonth) {
        return new ValidationStepContext(
                validationRunId,
                new ValidationJobContext(
                        validationMonth,
                        1L,
                        ValidationRunType.MONTHLY,
                        1L,
                        "req-cap-check"));
    }
}
