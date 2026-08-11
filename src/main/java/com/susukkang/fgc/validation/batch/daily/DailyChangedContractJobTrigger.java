package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.common.web.RequestIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * IF-BAT-02 @Scheduled 자동 실행 + 수동 재실행 진입점의 구현체
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyChangedContractJobTrigger {

    private final Job dailyChangedContractJob;
    private final JobLauncher jobLauncher;

    @Value("${fgc.batch.daily-changed-contract.triggered-by-user-id}")
    private long scheduledTriggeredByUserId;

    @Value("${fgc.batch.daily-changed-contract.enabled}")
    private boolean enabled;

    @Scheduled(cron = "${fgc.batch.daily-changed-contract.cron}", zone = "Asia/Seoul")
    public void runScheduled() {
        if (!enabled) {
            log.info("fgc.batch.daily-changed-contract.enabled=false — 자동 실행을 건너뜁니다.");
            return;
        }
        launch(scheduledTriggeredByUserId, RequestIdContext.generate());
    }

    /** 운영자가 화면/API에서 재실행을 요청했을 때 컨트롤러가 호출하는 진입점. */
    public void runManual(long triggeredBy, String requestId) {
        launch(triggeredBy, requestId);
    }

    private void launch(long triggeredBy, String requestId) {
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

        try {
            jobLauncher.run(dailyChangedContractJob, jobParameters);
        } catch (Exception e) {
            // JobLauncher.run()의 checked 예외 4종(JobInstanceAlreadyCompleteException 등)을
            // 여기서 다 구분해봐야 호출자가 할 수 있는 일이 없다(재실행은 requestId를 새로
            // 받아 다시 부르는 것뿐) — 로그만 남기고 흡수한다. @Scheduled 메서드에서 예외가
            // 새 나가면 이후 스케줄이 통째로 안 도는 Spring 스케줄러 특성도 피해야 한다.
            log.error("DailyChangedContractJob 실행 요청 실패 (requestId={})", requestId, e);
        }
    }
}
