package com.susukkang.fgc.validation.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** FGC-FUN-045: 실제 배치 기동·종료를 영속 재시도 이력에 연결한다. */
@RequiredArgsConstructor
public class ValidationRetryListener implements JobExecutionListener {
  private final JdbcTemplate jdbc;

  @Override
  public void beforeJob(JobExecution e) {
    Long id = e.getJobParameters().getLong("restartRetryId");
    if (id != null
        && jdbc.update(
                "UPDATE fgc.validation_run_retry SET"
                    + " state='RUNNING',batch_execution_id=?,started_at=clock_timestamp() WHERE"
                    + " retry_id=? AND state='ACCEPTED'",
                e.getId(),
                id)
            != 1) throw new IllegalStateException("재시작 접수가 유효하지 않습니다.");
  }

  @Override
  public void afterJob(JobExecution e) {
    Long id = e.getJobParameters().getLong("restartRetryId");
    if (id != null)
      jdbc.update(
          "UPDATE fgc.validation_run_retry SET state=?,finished_at=clock_timestamp(),error_code=?"
              + " WHERE retry_id=? AND state IN ('ACCEPTED','RUNNING')",
          e.getStatus() == BatchStatus.COMPLETED ? "SUCCEEDED" : "FAILED",
          e.getStatus() == BatchStatus.COMPLETED ? null : "BATCH_FAILED",
          id);
  }
}
