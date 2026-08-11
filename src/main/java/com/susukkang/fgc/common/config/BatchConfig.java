package com.susukkang.fgc.common.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Spring Boot의 Batch 자동설정이 기본으로 주는 JobLauncher는 동기(SyncTaskExecutor) —
 * jobLauncher.run(...)을 부른 스레드가 Job이 끝날 때까지 막힌다. #76 DailyChangedContractJob은
 * HTTP 요청 스레드(수동 재실행 API)나 Spring 스케줄러 스레드(@Scheduled 자동 실행)에서
 * launch되므로, Job이 도는 몇 분 동안 그 스레드를 붙들고 있으면 안 된다 — 그래서 비동기
 * TaskExecutor를 쓰는 JobLauncher로 기본 Bean을 교체한다.
 *
 * Bean 이름을 Boot 자동설정과 똑같이 "jobLauncher"로 두면 BeanDefinitionOverrideException이
 * 난다(Boot 3.5는 기본적으로 같은 이름 재정의를 허용하지 않음) — 그래서 이름을 다르게 짓고
 * @Primary를 붙였다. @Primary 덕분에 DailyChangedContractJobTrigger처럼 JobLauncher 타입만
 * 보고 주입받는 곳에서는 @Qualifier 없이도 항상 이 비동기 Bean이 선택된다.
 */
@Configuration
public class BatchConfig {

    @Primary
    @Bean
    public JobLauncher dailyChangedContractJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("daily-ccj-"));
        launcher.afterPropertiesSet();
        return launcher;
    }
}
