package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.RequestIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * IF-BAT-02 @Scheduled 자동 실행 + 수동 재실행 진입점의 구현체
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyChangedContractJobTrigger {

    private final ThreadPoolTaskExecutor taskExecutor = createExecutor();

    private final Job dailyChangedContractJob;
    private final JobLauncher jobLauncher;

    @Value("${fgc.batch.daily-changed-contract.triggered-by-user-id}")
    private long scheduledTriggeredByUserId;

    @Value("${fgc.batch.daily-changed-contract.enabled}")
    private boolean enabled;

    private static ThreadPoolTaskExecutor createExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("daily-ccj-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        // 대기열(10)까지 찬 뒤엔 새 launch 요청을 호출자 스레드에서 그대로 실행한다 —
        // 예외를 던져 요청을 그냥 버리는 것(AbortPolicy)보다, 느리더라도 실행은 되게 하는
        // 편이 배치 트리거 성격에 맞다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Scheduled(cron = "${fgc.batch.daily-changed-contract.cron}", zone = "Asia/Seoul")
    public void runScheduled() {
        if (!enabled) {
            log.info("fgc.batch.daily-changed-contract.enabled=false — 자동 실행을 건너뜁니다.");
            return;
        }
        // 스케줄러 스레드는 결과를 기다리는 호출자가 없다 — launch() 안의 로깅으로 충분하다.
        launch(scheduledTriggeredByUserId, RequestIdContext.generate());
    }

    /**
     * 운영자가 화면/API에서 재실행을 요청했을 때 호출하는 진입점
     */
    public CompletableFuture<JobExecution> runManual(long triggeredBy, String requestId) {
        return launch(triggeredBy, requestId);
    }

    private CompletableFuture<JobExecution> launch(long triggeredBy, String requestId) {
        String validationMonth = DateUtil.formatSettlementMonth(
                DateUtil.nowSeoul().toLocalDate().withDayOfMonth(1));

        // runNo: MonthlyValidationJobParameters가 두 Job의 공통 계약이라 형식상 필요하지만,
        // CreateDailyRunTasklet은 이 값을 쓰지 않는다(하루 1건 판정은 created_at 기준).
        // 그래서 항상 고정값 1을 채운다 — 실제로 채번되는 run_no는 ValidationRunCreateService가
        // (validationMonth, runType) 조합으로 별도로 매긴다.
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("validationMonth", validationMonth)
                .addLong("runNo", 1L)
                .addString("runType", ValidationRunType.MANUAL_CONTRACT.name())
                .addLong("triggeredBy", triggeredBy)
                .addString("requestId", requestId)
                .toJobParameters();

        // whenComplete는 exceptionally와 달리 예외를 다른 값으로 바꿔치기하지 않는다 — 로그만
        // 남기고, 반환된 CompletableFuture는 실패 상태를 그대로 유지해 호출자가 원하면 알 수 있다.
        return CompletableFuture
                .supplyAsync(() -> runOrWrap(jobParameters), taskExecutor)
                .whenComplete((jobExecution, ex) -> {
                    if (ex != null) {
                        log.error("DailyChangedContractJob 실행 요청 실패 (requestId={})", requestId, ex);
                    }
                });
    }

    private JobExecution runOrWrap(JobParameters jobParameters) {
        try {
            return jobLauncher.run(dailyChangedContractJob, jobParameters);
        } catch (Exception e) {
            // JobLauncher.run()의 checked 예외 4종(JobInstanceAlreadyCompleteException 등)을
            // CompletableFuture.supplyAsync의 Supplier는 checked exception을 못 던지므로
            // unchecked로 감싸 전달한다. 최종적으로 whenComplete에서 로그로 남는다.
            throw new IllegalStateException("DailyChangedContractJob launch 실패", e);
        }
    }
}
