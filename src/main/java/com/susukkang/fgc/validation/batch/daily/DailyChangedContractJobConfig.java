package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.contract.mapper.ContractMapper;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobExecutionListener;
import com.susukkang.fgc.validation.batch.ValidationRunStepProgressListener;
import com.susukkang.fgc.validation.mapper.BatchWatermarkMapper;
import com.susukkang.fgc.validation.mapper.ContractStatusEventProcessingMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IF-BAT-02 {@code DailyChangedContractJob} 골격
 *   1) createDailyRunStep  — "오늘" 쓸 MANUAL_CONTRACT validation_run 하나를 정하고,
 *                            watermark 시작점(lastProcessedAt)·이번 실행 시작시각(runStartedAt)을
 *                            Job ExecutionContext에 심는다.
 *   2) changedContractStep — watermark 이후 바뀐 계약만 청크 단위로 재검증하고, Step이
 *                            COMPLETED로 끝났을 때만 watermark를 전진시킨다.
 */
@Configuration
@RequiredArgsConstructor
public class DailyChangedContractJobConfig {

    private static final int CHUNK_SIZE = 200;

    private final ValidationRunMapper validationRunMapper;
    private final ValidationRunCreateService validationRunCreateService;
    private final ValidationRunTransitionService validationRunTransitionService;
    private final ValidationRunBatchLifecycleService validationRunBatchLifecycleService;
    private final ValidationRunBatchAuditService validationRunBatchAuditService;
    private final BatchWatermarkMapper batchWatermarkMapper;
    private final ContractMapper contractMapper;
    private final ScheduleService scheduleService;
    private final CapCheckService capCheckService;
    private final ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;
    private final ExceptionCaseMapper exceptionCaseMapper;

    @Bean
    public Job dailyChangedContractJob(JobRepository jobRepository,
                                        Step createDailyRunStep,
                                        Step changedContractStep) {
        return new JobBuilder(DailyChangedContractJobNames.JOB_NAME, jobRepository)
                .listener(new MonthlyValidationJobExecutionListener(validationRunBatchLifecycleService))
                .start(createDailyRunStep)
                .next(changedContractStep)
                .build();
    }

    @Bean
    public Step createDailyRunStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        CreateDailyRunTasklet tasklet = new CreateDailyRunTasklet(
                validationRunMapper,
                validationRunCreateService,
                validationRunTransitionService,
                validationRunBatchLifecycleService,
                batchWatermarkMapper);
        return new StepBuilder("createDailyRunStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .listener(progressListener(1, true))
                .build();
    }

    @Bean
    public Step changedContractStep(JobRepository jobRepository,
                                     PlatformTransactionManager transactionManager,
                                     JdbcPagingItemReader<Long> changedContractItemReader) {
        return new StepBuilder("changedContractStep", jobRepository)
                .<Long, ChangedContractResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(changedContractItemReader)
                .processor(changedContractItemProcessor())
                .writer(changedContractItemWriter())
                .listener(progressListener(2, false))
                .listener(new WatermarkAdvanceStepListener(batchWatermarkMapper))
                .build();
    }

    /**
     * lastProcessedAt은 @StepScope 프록시가 실제로 만들어지는 시점(Step 시작 직전)에야 값이
     * 확정되므로, 생성자 주입이 아니라 SpEL late-binding으로 받는다. createDailyRunStep이
     * DailyBatchContext.putLastProcessedAt(...)으로 Job ExecutionContext에 미리 심어 둔 값을
     * `#{jobExecutionContext['lastProcessedAt']}`로 읽는다.
     */
    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> changedContractItemReader(
            DataSource dataSource,
            @Value("#{jobExecutionContext['lastProcessedAt']}") String lastProcessedAtIso) {

        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("DISTINCT c.contract_id");
        queryProvider.setFromClause("fgc.insurance_contract c");
        queryProvider.setWhereClause("""
                (c.updated_at > :lastProcessedAt
                 OR EXISTS (
                     SELECT 1
                       FROM fgc.contract_status_event e
                      WHERE e.contract_id = c.contract_id
                        AND NOT EXISTS (
                            SELECT 1
                              FROM fgc.contract_status_event_processing p
                             WHERE p.contract_status_event_id = e.contract_status_event_id
                               AND p.processing_job = :jobName
                               AND p.processing_status = 'SUCCEEDED'
                        )
                 ))
                """);
        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put("contract_id", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);

        JdbcPagingItemReader<Long> reader = new JdbcPagingItemReader<>();
        reader.setName("changedContractItemReader");
        reader.setDataSource(dataSource);
        reader.setQueryProvider(queryProvider);
        reader.setPageSize(CHUNK_SIZE);
        reader.setParameterValues(Map.of(
                "lastProcessedAt", OffsetDateTime.parse(lastProcessedAtIso),
                "jobName", DailyChangedContractJobNames.JOB_NAME));
        reader.setRowMapper((rs, rowNum) -> rs.getLong("contract_id"));
        return reader;
    }

    private ChangedContractItemProcessor changedContractItemProcessor() {
        return new ChangedContractItemProcessor(
                contractMapper, scheduleService, capCheckService, contractStatusEventProcessingMapper);
    }

    private ChangedContractItemWriter changedContractItemWriter() {
        return new ChangedContractItemWriter(contractStatusEventProcessingMapper, exceptionCaseMapper);
    }

    private ValidationRunStepProgressListener progressListener(int stepNo, boolean initialStep) {
        return new ValidationRunStepProgressListener(
                stepNo, initialStep, validationRunBatchLifecycleService, validationRunBatchAuditService);
    }
}
