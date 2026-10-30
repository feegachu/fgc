package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.ContractEventJobRepository;
import com.susukkang.fgc.contract.repository.ContractEventJobRepository.JobKey;
import com.susukkang.fgc.contract.repository.ContractLockRepository;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.validation.repository.ContractStatusEventProcessingRepository;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 후속 작업 실패 기록의 분기. 실DB 순서·경합은 ContractStatusEventIntegrationTest가 검증한다. */
@ExtendWith(MockitoExtension.class)
class ContractStatusFollowUpServiceTest {

    private static final JobKey JOB = new JobKey(7L, "TestHoldJob");

    @Mock private ObjectProvider<ContractStatusFollowUpProcessor> processors;
    @Mock private ContractStatusFollowUpProcessor processor;
    @Mock private ContractEventJobRepository jobs;
    @Mock private ContractStatusEventRepository events;
    @Mock private ContractStatusEventProcessingRepository processingHistory;
    @Mock private ContractLockRepository contractLocks;
    @Mock private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    // 처리 실패 뒤 작업 행을 다시 잠갔을 때 아직 끝나지 않았을 때만 실패 이력·FAILED를 남긴다.
    // false는 그사이 다른 worker가 성공으로 끝낸 경우다 — 성공 이력 뒤에 실패 이력이 붙으면 안 된다.
    void recordsFailureOnlyWhileTheJobIsUnfinished(boolean unfinished) {
        ContractStatusEvent event = ContractStatusEvent.builder().contractId(21L).newStatus(ContractStatus.UNPAID).build();
        ReflectionTestUtils.setField(event, "contractStatusEventId", JOB.eventId());
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(jobs.claimNext(anyInt())).willReturn(Optional.of(JOB), Optional.empty());
        given(jobs.lockForProcessing(JOB)).willReturn(true);
        given(events.findById(JOB.eventId())).willReturn(Optional.of(event));
        given(processors.orderedStream()).willAnswer(invocation -> Stream.of(processor));
        given(processor.jobName()).willReturn(JOB.job());
        given(processor.process(event)).willThrow(new IllegalStateException("후속 처리 실패 주입"));
        given(jobs.lockForFailure(JOB)).willReturn(unfinished);

        ContractStatusFollowUpService service = new ContractStatusFollowUpService(
                processors, jobs, events, processingHistory, contractLocks, transactionManager);

        assertThat(service.runDueJobs()).isEqualTo(1);
        int expected = unfinished ? 1 : 0;
        verify(processingHistory, times(expected)).insertProcessing(
                JOB.eventId(), JOB.job(), "FAILED", null, "IllegalStateException: 후속 처리 실패 주입");
        verify(jobs, times(expected)).markFailed(JOB, "IllegalStateException");
        verify(processingHistory, never()).insertProcessing(any(), any(), org.mockito.ArgumentMatchers.eq("SUCCEEDED"),
                isNull(), isNull());
        verify(jobs, never()).markSucceeded(any());
    }
}
