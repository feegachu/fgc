package com.susukkang.fgc.common.persistence;

import com.susukkang.fgc.base.repository.InsurerRepository;
import com.susukkang.fgc.common.persistence.support.MixedPersistenceTestSupport;
import com.susukkang.fgc.common.persistence.support.MixedPersistenceTestSupport.MixedInsurerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.susukkang.fgc.common.persistence.support.MixedPersistenceTestSupport.newInsurer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 실제 Batch Step의 트랜잭션에 JPA와 MyBatis 저장이 함께 참여하는지 검증한다.
 * 테스트 자체에는 트랜잭션을 걸지 않아 Job 종료 후 DB의 커밋/롤백 결과를 확인한다.
 *
 * @author Codex
 * @version 1.0
 * @since 2026-09-24
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@ActiveProfiles("test")
@Import(MixedPersistenceTestSupport.class)
class JpaMyBatisBatchIntegrationTest {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InsurerRepository insurerRepository;

    @Autowired
    private MixedInsurerMapper mixedInsurerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String requestId = UUID.randomUUID().toString();
    private final String codePrefix = "BTX-" + requestId.substring(0, 12);
    private final String jpaCode = codePrefix + "-JPA";
    private final String myBatisCode = codePrefix + "-MB";
    private final List<JobExecution> createdExecutions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        try {
            jdbcTemplate.update(
                    "DELETE FROM fgc.insurer WHERE insurer_code IN (?, ?)", jpaCode, myBatisCode);
        } finally {
            // 인자 없는 removeJobExecutions()는 다른 테스트의 실행까지 지우므로 사용하지 않는다.
            new JobRepositoryTestUtils(jobRepository).removeJobExecutions(createdExecutions);
        }
    }

    @Test
    void completedStepCommitsJpaAndMyBatisWritesTogether() throws Exception {
        JobExecution execution = launchMixedWriteJob(false);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getAllFailureExceptions()).isEmpty();
        assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(step.getCommitCount()).isEqualTo(1);
            assertThat(step.getRollbackCount()).isZero();
        });
        assertThat(storedInsurerCodes()).containsExactlyInAnyOrder(jpaCode, myBatisCode);
    }

    @Test
    void failedStepRollsBackJpaAndMyBatisWritesTogether() throws Exception {
        JobExecution execution = launchMixedWriteJob(true);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(step.getCommitCount()).isZero();
            assertThat(step.getRollbackCount()).isEqualTo(1);
            assertThat(step.getFailureExceptions()).anySatisfy(failure ->
                    assertThat(failure).isInstanceOf(ForcedBatchRollback.class));
        });
        assertThat(storedInsurerCodes()).isEmpty();
    }

    private JobExecution launchMixedWriteJob(boolean failAfterWrites) throws Exception {
        Step step = new StepBuilder("mixedPersistenceWriteStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

                    insurerRepository.saveAndFlush(newInsurer(jpaCode));
                    assertThat(mixedInsurerMapper.insert(myBatisCode, "배치 MyBatis 보험사"))
                            .isEqualTo(1);

                    // 두 INSERT가 실제 실행된 후 예외를 내야 롤백 검증이 의미가 있다.
                    assertThat(storedInsurerCodes()).containsExactlyInAnyOrder(jpaCode, myBatisCode);
                    if (failAfterWrites) {
                        throw new ForcedBatchRollback();
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
        Job job = new JobBuilder("mixedPersistenceTestJob", jobRepository)
                .start(step)
                .build();

        JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
                .addString("requestId", requestId)
                .toJobParameters());
        createdExecutions.add(execution);
        return execution;
    }

    private List<String> storedInsurerCodes() {
        return jdbcTemplate.queryForList(
                "SELECT insurer_code FROM fgc.insurer WHERE insurer_code IN (?, ?)",
                String.class, jpaCode, myBatisCode);
    }

    private static final class ForcedBatchRollback extends RuntimeException {
        private ForcedBatchRollback() {
            super("JPA와 MyBatis 저장 후 배치 롤백 검증");
        }
    }
}
