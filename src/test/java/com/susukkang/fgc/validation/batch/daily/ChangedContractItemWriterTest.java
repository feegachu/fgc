package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 성공/데이터품질스킵 두 경로가 각각 contract_status_event_processing과 exception_case에
 * 올바른 값을 남기는지 검증한다. validationRunId는 @BeforeStep으로 Job ExecutionContext에서
 * 읽으므로, 테스트에서도 실제 StepExecution을 통해 beforeStep을 먼저 호출해 준다.
 */
@ExtendWith(MockitoExtension.class)
class ChangedContractItemWriterTest {

    @Mock
    private ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;
    @Mock
    private ExceptionCaseMapper exceptionCaseMapper;

    private ChangedContractItemWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ChangedContractItemWriter(contractStatusEventProcessingMapper, exceptionCaseMapper);

        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, DailyChangedContractJobNames.JOB_NAME), new JobParameters());
        jobExecution.getExecutionContext().putLong("validationRunId", 777L);
        writer.beforeStep(new StepExecution("changedContractStep", jobExecution));
    }

    @Test
    void successResultMarksPendingEventsSucceeded() {
        // pendingEventIds는 이제 Writer가 다시 조회하지 않고 Processor가 넘겨준 값을 그대로
        // 쓴다(TOCTOU 레이스 방지, 코드리뷰 반영) — 그래서 결과 자체에 담아 전달한다.
        writer.write(new Chunk<>(List.of(ChangedContractResult.success(1L, List.of(10L, 11L)))));

        verify(contractStatusEventProcessingMapper).insertProcessing(10L, DailyChangedContractJobNames.JOB_NAME, "SUCCEEDED", 777L, null);
        verify(contractStatusEventProcessingMapper).insertProcessing(11L, DailyChangedContractJobNames.JOB_NAME, "SUCCEEDED", 777L, null);
        verifyNoInteractions(exceptionCaseMapper);
    }

    @Test
    void dataQualitySkipResultCreatesExceptionCaseAndMarksEventsFailed() {
        writer.write(new Chunk<>(List.of(
                ChangedContractResult.dataQualitySkip(2L, "FGC-CONT-001", List.of(20L)))));

        verify(exceptionCaseMapper).insertDataQualityCase(eq(777L), eq(2L), eq("일일 변경 계약 재검증 실패"), eq("FGC-CONT-001"));
        verify(contractStatusEventProcessingMapper).insertProcessing(20L, DailyChangedContractJobNames.JOB_NAME, "FAILED", 777L, "FGC-CONT-001");
    }
}
