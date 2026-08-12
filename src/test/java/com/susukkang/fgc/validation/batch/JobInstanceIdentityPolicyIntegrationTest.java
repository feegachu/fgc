package com.susukkang.fgc.validation.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "동일한 JobParameter로 Job을 다시 실행할 때 Spring Batch의 동일 Job 인스턴스 정책이
 * 적용되는지" / "새 실행이 필요한 경우 runNo·requestId 등 고유 식별자를 포함해 별도 Job
 * 인스턴스로 실행되는지" 검증
 */
@SpringBootTest
class JobInstanceIdentityPolicyIntegrationTest {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Job trivialAlwaysSucceedingJob() {
        var step = new StepBuilder("trivialStep", jobRepository)
                .tasklet((contribution, chunkContext) -> org.springframework.batch.repeat.RepeatStatus.FINISHED,
                        transactionManager)
                .build();
        return new JobBuilder("trivialIdempotencyTestJob", jobRepository)
                .start(step)
                .build();
    }

    @Test
    void relaunchingWithIdenticalJobParametersAfterCompletionIsRejected() throws Exception {
        Job job = trivialAlwaysSucceedingJob();
        JobParameters parameters = new JobParametersBuilder()
                .addString("requestId", "req-fixed-" + System.nanoTime())
                .toJobParameters();

        var first = jobLauncher.run(job, parameters);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 같은 JobParameters로 다시 실행하면 Spring Batch가 "이미 끝난 같은 JobInstance"로
        // 판단해 새로 실행해주지 않는다 — 우리가 구현한 게 아니라 프레임워크가 보장하는 정책이다.
        assertThatThrownBy(() -> jobLauncher.run(job, parameters))
                .isInstanceOf(JobInstanceAlreadyCompleteException.class);
    }

    @Test
    void relaunchingWithNewIdentifyingParameterCreatesSeparateJobInstance()
            throws JobExecutionAlreadyRunningException, JobRestartException,
            JobInstanceAlreadyCompleteException, JobParametersInvalidException {
        Job job = trivialAlwaysSucceedingJob();
        String base = "req-distinct-" + System.nanoTime();

        JobParameters first = new JobParametersBuilder()
                .addString("requestId", base + "-1")
                .toJobParameters();
        JobParameters second = new JobParametersBuilder()
                .addString("requestId", base + "-2")
                .toJobParameters();

        var firstExecution = jobLauncher.run(job, first);
        var secondExecution = jobLauncher.run(job, second);

        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(secondExecution.getJobInstance().getInstanceId())
                .isNotEqualTo(firstExecution.getJobInstance().getInstanceId());
    }
}
