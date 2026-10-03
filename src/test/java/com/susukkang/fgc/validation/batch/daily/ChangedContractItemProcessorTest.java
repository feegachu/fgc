package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.repository.ContractStatusEventProcessingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChangedContractItemProcessorTest {

    @Mock
    private InsuranceContractRepository insuranceContractRepository;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private CapCheckService capCheckService;
    @Mock
    private ContractStatusEventProcessingRepository contractStatusEventProcessingRepository;

    private ChangedContractItemProcessor processor;
    private final OffsetDateTime watermark = OffsetDateTime.parse("2026-08-10T02:00:00+09:00");

    @BeforeEach
    void setUp() {
        processor = new ChangedContractItemProcessor(
                insuranceContractRepository, scheduleService, capCheckService, contractStatusEventProcessingRepository);

        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, DailyChangedContractJobNames.JOB_NAME), new JobParameters());
        jobExecution.getExecutionContext().putLong("validationRunId", 777L);
        jobExecution.getExecutionContext().putString("lastProcessedAt", watermark.toString());
        processor.beforeStep(new StepExecution("changedContractStep", jobExecution));
    }

    // 기본 계약은 watermark보다 이전에 바뀐 것으로 만든다 — 그래야 순수 상태 이벤트 유무만으로
    // 재생성 여부가 갈리는 기존 테스트들의 전제가 그대로 유지된다.
    private InsuranceContract contract(long contractId) {
        InsuranceContract contract = InsuranceContract.builder().build();
        ReflectionTestUtils.setField(contract, "contractId", contractId);
        ReflectionTestUtils.setField(contract, "updatedAt", watermark.minusDays(1));
        return contract;
    }

    @Test
    void pendingStatusEventTriggersScheduleRegenerationAndChecksBothPaymentStages() {
        given(insuranceContractRepository.findById(1L)).willReturn(Optional.of(contract(1L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(100L));

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.contractId()).isEqualTo(1L);
        assertThat(result.pendingEventIds()).containsExactly(100L);
        verify(scheduleService).generateSchedulesInNewTransaction(any());

        // cap_check가 이 배치의 validation_run에 실제로 연결되는지 확인한다(코드리뷰 지적,
        // 2026-08-11) — CapCalculationCommand.realtime()을 그대로 썼다면 validationRunId가
        // 항상 null이라 uq_cap_check_monthly 중복방지가 무력화되고 §7-5 "그 밑에 결과를
        // 붙인다"도 어긋난다.
        ArgumentCaptor<CapCalculationCommand> captor = ArgumentCaptor.forClass(CapCalculationCommand.class);
        // PaymentStage 2개(INSURER_TO_GA, GA_TO_FC) 각각 1번씩 -> 총 2번
        verify(capCheckService, times(2)).calculateAndSave(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(command -> assertThat(command.validationRunId()).isEqualTo(777L));
    }

    @Test
    void noPendingStatusEventSkipsScheduleRegenerationButStillChecksCap() {
        given(insuranceContractRepository.findById(1L)).willReturn(Optional.of(contract(1L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of());

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        verify(scheduleService, never()).generateSchedulesInNewTransaction(any());
        verify(capCheckService, times(2)).calculateAndSave(any(CapCalculationCommand.class));
    }

    // FGC-FUN-039의 "언제" 트리거는 상태 이벤트뿐 아니라 "계약·정책 변경"도 포함한다.
    // ContractService#updateContract가 보험료·납입기간 등을 바꿔도 contract_status_event를
    // 안 남기는 경로가 있어서, 상태 이벤트 유무만으로 재생성을 판단하면 이 경로가 조용히
    // 누락된다(코드리뷰 지적, 2026-08-11) — updated_at이 watermark 이후인지도 같이 본다.
    @Test
    void contractUpdatedAfterWatermarkTriggersScheduleRegenerationEvenWithoutStatusEvent() {
        InsuranceContract contract = contract(1L);
        ReflectionTestUtils.setField(contract, "updatedAt", watermark.plusHours(1));
        given(insuranceContractRepository.findById(1L)).willReturn(Optional.of(contract));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of());

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        verify(scheduleService).generateSchedulesInNewTransaction(any());
    }

    @Test
    void missingContractIsTreatedAsDataQualitySkip() {
        given(insuranceContractRepository.findById(2L)).willReturn(Optional.empty());

        ChangedContractResult result = processor.process(2L);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("2");
    }

    @Test
    void businessExceptionDuringScheduleGenerationIsTreatedAsDataQualitySkipNotStepFailure() {
        given(insuranceContractRepository.findById(3L)).willReturn(Optional.of(contract(3L)));
        given(contractStatusEventProcessingRepository.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(100L));
        willThrow(new FgcBusinessException(FgcErrorCode.CONT_001, Map.of()))
                .given(scheduleService).generateSchedulesInNewTransaction(any());

        ChangedContractResult result = processor.process(3L);

        assertThat(result.success()).isFalse();
        assertThat(result.contractId()).isEqualTo(3L);
        assertThat(result.failureReason()).contains(FgcErrorCode.CONT_001.getCode());
    }
}
