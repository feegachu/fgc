package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.contract.ReconciliationBatchPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FGC-FUN-041: 대사 Step이 필수 실행 컨텍스트 누락을 명확하게 차단하는지 검증한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationTaskletTest {

    @Mock
    private ReconciliationBatchPort reconciliationBatchPort;

    @Test
    void validationRunId가_없으면_대사를_호출하지_않고_즉시_실패한다() {
        ReconciliationTasklet tasklet = new ReconciliationTasklet(reconciliationBatchPort);

        assertThatThrownBy(() -> tasklet.execute(null, newChunkContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FGC-FUN-041")
                .hasMessageContaining("validationRunId");
        verifyNoInteractions(reconciliationBatchPort);
    }

    private static ChunkContext newChunkContext() {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 1L)
                .addString("requestId", "IT-048-04-MISSING-CONTEXT")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, "MonthlyValidationJob"), parameters);
        StepExecution stepExecution = new StepExecution("reconciliationStep", jobExecution);
        return new ChunkContext(new StepContext(stepExecution));
    }
}
