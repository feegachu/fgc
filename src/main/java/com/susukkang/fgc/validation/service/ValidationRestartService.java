package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.common.security.Roles;
import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.dto.*;
import com.susukkang.fgc.validation.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** FGC-FUN-045: 접수의 커밋과 기동을 분리하고 실패한 원실행의 식별자를 유지한다. */
@Service
@RequiredArgsConstructor
public class ValidationRestartService {
  private final ValidationRunRepository runs;
  private final MonthlyJobExecutionLookup executions;
  private final MonthlyValidationJobTrigger trigger;
  private final JdbcTemplate jdbc;
  private final PlatformTransactionManager transactions;

  public record Restartability(
      boolean allowed,
      String reasonCode,
      String reason,
      String expectedStateVersion,
      Long lastFailedBatchExecutionId,
      Integer failedStep,
      boolean checkpointAvailable,
      Long completedCount,
      Long remainingCount,
      java.time.OffsetDateTime failedAt) {}

  public record Accepted(
      Long validationRunId,
      Long retryId,
      Long previousBatchExecutionId,
      Long batchExecutionId,
      String status) {}

  public record Retry(
      Long retryId,
      Long previousBatchExecutionId,
      Long batchExecutionId,
      Integer failedStep,
      Long requestedBy,
      String reason,
      String state,
      java.time.OffsetDateTime requestedAt,
      java.time.OffsetDateTime startedAt,
      java.time.OffsetDateTime finishedAt,
      String errorCode) {}

  private record Claim(Accepted response, ValidationRunRow run) {}

  public Restartability eligibility(Long id) {
    var run = runs.findById(id).orElseThrow(() -> missing(id));
    String version = run.getStatus().name() + ":" + run.getCurrentStep();
    Long executionId = null;
    boolean checkpoint = false;
    String reason = null;
    if (run.getStatus() != ValidationRunStatus.FAILED || "MANUAL_CONTRACT".equals(run.getRunType()))
      reason = "실패한 월 검증만 재시작할 수 있습니다.";
    else
      try {
        var previous = executions.findFailedExecution(ValidationRunRow.from(run));
        executionId = previous.getId();
        checkpoint =
            id.equals(
                com.susukkang.fgc.validation.batch.ValidationRunBatchContext.getValidationRunId(
                    previous.getExecutionContext()));
        if (!checkpoint) reason = "이어서 처리할 지점이 없습니다.";
      } catch (IllegalStateException e) {
        reason = "실패한 실행 기록을 확인할 수 없습니다.";
      }
    version += ":" + executionId;
    if (active(id)) reason = "이미 재시작 요청이 진행 중입니다.";
    if (otherActive(id, run.getValidationMonth())) reason = "같은 월에 다른 실행이 진행 중입니다.";
    return new Restartability(
        reason == null,
        reason == null ? null : "RESTART_UNAVAILABLE",
        reason,
        version,
        executionId,
        run.getCurrentStep(),
        checkpoint,
        null,
        null,
        run.getCompletedAt());
  }

  private boolean active(Long id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM fgc.validation_run_retry WHERE validation_run_id=? AND"
                + " state IN ('ACCEPTED','LAUNCHING','RUNNING'))",
            Boolean.class,
            id));
  }

  private boolean otherActive(Long id, java.time.LocalDate month) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM fgc.validation_run v WHERE validation_month=? AND"
                + " validation_run_id<>? AND run_type IN ('MONTHLY','PRE_CONFIRM') AND (status IN"
                + " ('CREATED','RUNNING') OR EXISTS(SELECT 1 FROM fgc.validation_run_retry r WHERE"
                + " r.validation_run_id=v.validation_run_id AND r.state IN"
                + " ('ACCEPTED','LAUNCHING','RUNNING'))))",
            Boolean.class,
            month,
            id));
  }

  @PreAuthorize(Roles.CAN_PROCESS)
  public Accepted restart(Long id, Long user, String key, ValidationRestartRequest request) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{1,100}"))
      throw new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("field", "Idempotency-Key"));
    String hash =
        hash(
            request.expectedBatchExecutionId()
                + "\n"
                + request.expectedStateVersion()
                + "\n"
                + request.reason());
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    Claim claim =
        template.execute(
            tx -> {
              var initial = runs.findById(id).orElseThrow(() -> missing(id));
              runs.lockValidationMonth(initial.getValidationMonth());
              runs.lockValidationRunRestart(id);
              // JPA 캐시가 아니라 잠근 최신 행을 기준으로 한다.
              var locked = runs.findReferenceForUpdate(id).orElseThrow(() -> missing(id));
              var old =
                  jdbc.queryForList(
                      "SELECT retry_id,previous_batch_execution_id,request_hash FROM"
                          + " fgc.validation_run_retry WHERE validation_run_id=? AND requested_by=?"
                          + " AND idempotency_key=?",
                      id,
                      user,
                      key);
              if (!old.isEmpty()) {
                var r = old.getFirst();
                if (!hash.equals(r.get("request_hash"))) throw conflict();
                return new Claim(
                    new Accepted(
                        id,
                        ((Number) r.get("retry_id")).longValue(),
                        ((Number) r.get("previous_batch_execution_id")).longValue(),
                        null,
                        "ACCEPTED"),
                    null);
              }
              var check = eligibility(id);
              if (!"FAILED".equals(locked.getStatus())
                  || !check.allowed()
                  || !Objects.equals(
                      request.expectedBatchExecutionId(), check.lastFailedBatchExecutionId())
                  || !Objects.equals(request.expectedStateVersion(), check.expectedStateVersion()))
                throw conflict();
              Long retry =
                  jdbc.queryForObject(
                      "INSERT INTO"
                          + " fgc.validation_run_retry(validation_run_id,previous_batch_execution_id,requested_by,idempotency_key,request_hash,reason,failed_step,state)"
                          + " VALUES (?,?,?,?,?,?,?,'ACCEPTED') RETURNING retry_id",
                      Long.class,
                      id,
                      request.expectedBatchExecutionId(),
                      user,
                      key,
                      hash,
                      request.reason(),
                      initial.getCurrentStep());
              return new Claim(
                  new Accepted(id, retry, request.expectedBatchExecutionId(), null, "ACCEPTED"),
                  ValidationRunRow.from(initial));
            });
    launchClaim(claim);
    return claim.response();
  }

  private void launchClaim(Claim claim) {
    if (claim.run() != null) {
      try {
        trigger
            .launchRestart(
                claim.run(),
                com.susukkang.fgc.common.web.RequestIdContext.current(),
                claim.response().retryId())
            .whenComplete(
                (execution, error) -> {
                  if (error != null) fail(claim.response().retryId());
                });
      } catch (RuntimeException e) {
        fail(claim.response().retryId());
        throw new FgcBusinessException(FgcErrorCode.VRUN_008);
      }
    }
  }

  /** 기동되지 않은 오래된 접수만 만료한다. 실행 중인 Batch를 임의로 FAILED로 바꾸지 않는다. */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
  public void expireUnstartedRequests() {
    jdbc.update(
        "UPDATE fgc.validation_run_retry SET"
            + " state='FAILED',finished_at=clock_timestamp(),error_code='LAUNCH_TIMEOUT' WHERE"
            + " state='ACCEPTED' AND batch_execution_id IS NULL AND requested_at <"
            + " clock_timestamp()-interval '5 minutes'");
  }

  private void fail(Long retry) {
    jdbc.update(
        "UPDATE fgc.validation_run_retry SET"
            + " state='FAILED',finished_at=clock_timestamp(),error_code='LAUNCH_FAILED' WHERE"
            + " retry_id=? AND state IN ('ACCEPTED','LAUNCHING','RUNNING')",
        retry);
  }

  public List<Retry> history(Long id) {
    if (!runs.existsById(id)) throw missing(id);
    return jdbc.query(
        "SELECT * FROM fgc.validation_run_retry WHERE validation_run_id=? ORDER BY retry_id DESC"
            + " LIMIT 100",
        (r, n) ->
            new Retry(
                r.getLong("retry_id"),
                r.getLong("previous_batch_execution_id"),
                r.getObject("batch_execution_id", Long.class),
                r.getObject("failed_step", Integer.class),
                r.getLong("requested_by"),
                r.getString("reason"),
                r.getString("state"),
                r.getObject("requested_at", java.time.OffsetDateTime.class),
                r.getObject("started_at", java.time.OffsetDateTime.class),
                r.getObject("finished_at", java.time.OffsetDateTime.class),
                r.getString("error_code")),
        id);
  }

  private static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static FgcBusinessException conflict() {
    return new FgcBusinessException(FgcErrorCode.VRUN_005);
  }

  private static FgcBusinessException missing(Long id) {
    return new FgcBusinessException(FgcErrorCode.COMMON_004, Map.of("id", id));
  }
}
