package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.InsuranceContract;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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
    private ContractMapper contractMapper;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private CapCheckService capCheckService;
    @Mock
    private ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;

    private ChangedContractItemProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ChangedContractItemProcessor(
                contractMapper, scheduleService, capCheckService, contractStatusEventProcessingMapper);
    }

    private InsuranceContract contract(long contractId) {
        return InsuranceContract.builder().contractId(contractId).build();
    }

    @Test
    void pendingStatusEventTriggersScheduleRegenerationAndChecksBothPaymentStages() {
        given(contractMapper.selectById(1L)).willReturn(contract(1L));
        given(contractStatusEventProcessingMapper.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(100L));

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        assertThat(result.contractId()).isEqualTo(1L);
        verify(scheduleService).generateSchedules(any());
        // PaymentStage 2개(INSURER_TO_GA, GA_TO_FC) 각각 1번씩 -> 총 2번
        verify(capCheckService, times(2)).calculateAndSave(any(CapCalculationCommand.class));
    }

    @Test
    void noPendingStatusEventSkipsScheduleRegenerationButStillChecksCap() {
        given(contractMapper.selectById(1L)).willReturn(contract(1L));
        given(contractStatusEventProcessingMapper.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of());

        ChangedContractResult result = processor.process(1L);

        assertThat(result.success()).isTrue();
        verify(scheduleService, never()).generateSchedules(any());
        verify(capCheckService, times(2)).calculateAndSave(any(CapCalculationCommand.class));
    }

    @Test
    void missingContractIsTreatedAsDataQualitySkip() {
        given(contractMapper.selectById(2L)).willReturn(null);

        ChangedContractResult result = processor.process(2L);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("2");
    }

    @Test
    void businessExceptionDuringScheduleGenerationIsTreatedAsDataQualitySkipNotStepFailure() {
        given(contractMapper.selectById(3L)).willReturn(contract(3L));
        given(contractStatusEventProcessingMapper.findPendingEventIds(anyLong(), anyString()))
                .willReturn(List.of(100L));
        willThrow(new FgcBusinessException(FgcErrorCode.CONT_001, Map.of()))
                .given(scheduleService).generateSchedules(any());

        ChangedContractResult result = processor.process(3L);

        assertThat(result.success()).isFalse();
        assertThat(result.contractId()).isEqualTo(3L);
        assertThat(result.failureReason()).contains(FgcErrorCode.CONT_001.getCode());
    }
}
