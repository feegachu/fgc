package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceCheckPort;
import com.susukkang.fgc.validation.batch.tasklet.CreateRunTasklet;
import com.susukkang.fgc.validation.batch.tasklet.LedgerImbalanceCheckTasklet;
import com.susukkang.fgc.validation.batch.tasklet.PlaceholderStepTasklet;
import com.susukkang.fgc.validation.batch.tasklet.ReconciliationPlaceholderTasklet;
import com.susukkang.fgc.validation.batch.tasklet.SelectTargetTasklet;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationTargetSelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * IF-BAT-01 {@code MonthlyValidationJob} 골격
 *
 * 8단계 순서와 진행상황 기록 구조
 * 후에 각각 별도 이슈에서 PlaceholderStepTasklet 자리를 실제 구현으로 바꿈
 *
 * current_step(0~10) 매핑: 실행생성=1(RUNNING 전이와 동시), 대상선별=2, 스케줄재생성=3,
 * 1200%검증=4, 차익거래검증=5, 원장기표·균형검사=6(journalPostingStep·imbalanceCheckStep
 */
@Configuration
@RequiredArgsConstructor
public class MonthlyValidationJobConfig {

    // Tasklet 안에서 실제 업무 로직을 부를 때 필요한 서비스 2개
    private final ValidationRunCreateService validationRunCreateService;
    private final ValidationRunBatchLifecycleService validationRunBatchLifecycleService;
    private final ValidationRunBatchAuditService validationRunBatchAuditService;
    private final ValidationTargetSelectionService validationTargetSelectionService;
    // #98: imbalanceCheckStep(⑥균형검사)이 쓴다. journalPostingStep(⑥기표)은 아직
    // JournalPostingPort 구현체가 없어 PlaceholderStepTasklet 그대로 둔다.
    private final LedgerImbalanceCheckPort ledgerImbalanceCheckPort;

    @Bean
    public Job monthlyValidationJob(JobRepository jobRepository,
                                     Step createRunStep,
                                     Step selectTargetStep,
                                     Step regenerateScheduleStep,
                                     Step capCheckStep,
                                     Step arbitrageCheckStep,
                                     Step journalPostingStep,
                                     Step imbalanceCheckStep,
                                     Step reconciliationStep,
                                     Step exceptionGenerationStep) {
        return new JobBuilder("MonthlyValidationJob", jobRepository)
                .validator(new MonthlyValidationJobParametersValidator())
                .listener(new MonthlyValidationJobExecutionListener(validationRunBatchLifecycleService))
                .start(createRunStep)
                .next(selectTargetStep)
                .next(regenerateScheduleStep)
                .next(capCheckStep)
                .next(arbitrageCheckStep)
                .next(journalPostingStep)
                .next(imbalanceCheckStep)
                .next(reconciliationStep)
                .next(exceptionGenerationStep)
                .build();
    }

    /**
     * 1) 실행생성 Step
     * CreateRunTasklet이 기존 ValidationRunCreateService를 호출해서 validation_run 행을 실제로 만듬
     */
    @Bean
    public Step createRunStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("createRunStep", jobRepository)
                .tasklet(new CreateRunTasklet(validationRunCreateService), transactionManager)
                .listener(progressListener(1, true))
                .build();
    }

    /**
     * 아직 실제 로직이 없는 Step
     */
    @Bean
    public Step selectTargetStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("selectTargetStep", jobRepository)
                .tasklet(new SelectTargetTasklet(validationTargetSelectionService), transactionManager)
                .listener(progressListener(2, false))
                .build();
    }

    @Bean
    public Step regenerateScheduleStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("regenerateScheduleStep", jobRepository)
                .tasklet(new PlaceholderStepTasklet("③현행 예상 스케줄 생성·재검증"), transactionManager)
                .listener(progressListener(3, false))
                .build();
    }

    /**
     * capCheckStep의 실제 워크로드를 처리하는 "워커" Step
     */
    @Bean
    public Step capCheckWorkerStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("capCheckWorkerStep", jobRepository)
                .tasklet(new PlaceholderStepTasklet("④지급단계별 1,200% 검증"), transactionManager)
                .build();
    }

    /**
     * capCheckStep "매니저"
     */
    @Bean
    public Step capCheckStep(JobRepository jobRepository, Step capCheckWorkerStep) {
        return new StepBuilder("capCheckStep", jobRepository)
                .partitioner(capCheckWorkerStep.getName(), new PaymentStagePartitioner())
                .step(capCheckWorkerStep)
                .taskExecutor(new SyncTaskExecutor())
                .listener(progressListener(4, false))
                .build();
    }

    // 차익거래 검증 및 원장 불균형 재검증은 로직 구현 후 추가 예정
    @Bean
    public Step arbitrageCheckStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("arbitrageCheckStep", jobRepository)
                .tasklet(new PlaceholderStepTasklet("⑤차익거래 검증"), transactionManager)
                .listener(progressListener(5, false))
                .build();
    }

    @Bean
    public Step journalPostingStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("journalPostingStep", jobRepository)
                .tasklet(new PlaceholderStepTasklet("⑥검증원장 기표"), transactionManager)
                .listener(progressListener(6, false))
                .build();
    }

    @Bean
    public Step imbalanceCheckStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("imbalanceCheckStep", jobRepository)
                .tasklet(new LedgerImbalanceCheckTasklet(ledgerImbalanceCheckPort), transactionManager)
                .listener(progressListener(6, false))
                .build();
    }

    @Bean
    public Step reconciliationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("reconciliationStep", jobRepository)
                .tasklet(new ReconciliationPlaceholderTasklet(), transactionManager)
                .listener(progressListener(7, false))
                .build();
    }

    @Bean
    public Step exceptionGenerationStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("exceptionGenerationStep", jobRepository)
                .tasklet(new PlaceholderStepTasklet("⑧예외 생성"), transactionManager)
                .listener(progressListener(8, false))
                .build();
    }

    // @Bean 메서드마다 "new ValidationRunStepProgressListener(번호, ..., 서비스)"를 반복해서 쓰지 않으려고 뽑아 둔 헬퍼
    private ValidationRunStepProgressListener progressListener(int stepNo, boolean initialStep) {
        return new ValidationRunStepProgressListener(
                stepNo, initialStep, validationRunBatchLifecycleService, validationRunBatchAuditService);
    }
}
