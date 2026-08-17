package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * IF-API-48 실행 요청을 받아 MonthlyValidationJob(IF-BAT-01)을 비동기로 기동한다.
 * DailyChangedContractJobTrigger와 같은 패턴 — 단, 스케줄 경로는 없다(월 통합검증은
 * 1차에서 사용자가 화면에서 수동 실행한다, 인터페이스정의서 §7-2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyValidationJobTrigger {

    private final ThreadPoolTaskExecutor taskExecutor = createExecutor();

    private final Job monthlyValidationJob;
    private final JobLauncher jobLauncher;
    private final ValidationRunMapper validationRunMapper;

    private static ThreadPoolTaskExecutor createExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("monthly-vj-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        // 대기열(10)까지 찬 뒤엔 즉시 거부한다(AbortPolicy). CallerRunsPolicy 를 쓰면 넘친
        // 실행이 HTTP 호출자 스레드에서 동기로 돌아 IF-API-48 의 "202 즉시 반환" 계약이
        // 깨진다 — 거부는 ValidationRunExecuteServiceImpl 이 VRUN_005(재시도 안내)로 바꾼다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    // taskExecutor는 Spring @Bean이 아니라 필드 초기화식으로 직접 만든 POJO라, 컨테이너가
    // 종료될 때 자동으로 destroy()를 불러주지 않는다. @PreDestroy로 명시적으로 shutdown 한다.
    @PreDestroy
    public void shutdown() {
        taskExecutor.shutdown();
    }

    /**
     * 행 값(validationMonth·runNo·runType·triggeredBy)으로 JobParameters를 만들어 기동한다.
     * createRunStep이 ValidationRunCreateService의 멱등 경로(runNo 명시)를 다시 타므로,
     * 파라미터가 기존 행과 정확히 일치해야 기존 행을 재사용한다 — 호출자(실행 버튼을 누른
     * 사용자) 값을 쓰면 triggeredBy 불일치로 VRUN_005가 난다.
     */
    public CompletableFuture<JobExecution> launch(ValidationRunRow run, String requestId) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("validationMonth", DateUtil.formatSettlementMonth(run.getValidationMonth()))
                .addLong("runNo", run.getRunNo().longValue())
                .addString("runType", run.getRunType())
                .addLong("triggeredBy", run.getTriggeredBy())
                .addString("requestId", requestId)
                .toJobParameters();

        Long validationRunId = run.getValidationRunId();

        // whenComplete는 exceptionally와 달리 예외를 다른 값으로 바꿔치기하지 않는다 — 로그만
        // 남기고, 반환된 CompletableFuture는 실패 상태를 그대로 유지해 호출자가 원하면 알 수 있다.
        return CompletableFuture
                .supplyAsync(() -> runOrWrap(validationRunId, jobParameters), taskExecutor)
                .whenComplete((jobExecution, ex) -> {
                    if (ex != null) {
                        log.error("MonthlyValidationJob 실행 요청 실패 (validationRunId={}, requestId={})",
                                validationRunId, requestId, ex);
                    }
                });
    }

    private JobExecution runOrWrap(Long validationRunId, JobParameters jobParameters) {
        // 단일 스레드 executor 안에서 상태를 재확인한다 — API guard(CREATED)와 이 재확인
        // 사이에 끼어든 중복 클릭은 여기서 걸러져 배치 메타에 쓰레기 FAILED 실행을 남기지 않는다.
        ValidationRunRow fresh = validationRunMapper.findById(validationRunId);
        if (fresh == null || !ValidationRunStatus.CREATED.name().equals(fresh.getStatus())) {
            throw new IllegalStateException("validation_run " + validationRunId + " 은 이미 기동됨(status="
                    + (fresh == null ? "삭제됨" : fresh.getStatus()) + ") — 중복 실행 요청을 무시합니다.");
        }
        try {
            return jobLauncher.run(monthlyValidationJob, jobParameters);
        } catch (Exception e) {
            // JobLauncher.run()의 checked 예외 4종을 supplyAsync의 Supplier는 못 던지므로
            // unchecked로 감싸 전달한다. 최종적으로 whenComplete에서 로그로 남는다.
            throw new IllegalStateException("MonthlyValidationJob launch 실패", e);
        }
    }
}
