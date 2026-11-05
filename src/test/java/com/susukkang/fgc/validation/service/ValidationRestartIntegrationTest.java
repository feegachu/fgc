package com.susukkang.fgc.validation.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.susukkang.fgc.validation.batch.MonthlyValidationJobTrigger;
import com.susukkang.fgc.validation.dto.ValidationRestartRequest;
import com.susukkang.fgc.validation.repository.MonthlyJobExecutionLookup;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** FGC-FUN-045: 실제 DB의 동시 claim·멱등·실패 정리를 검증한다. */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@WithMockUser(roles = "SETTLEMENT")
class ValidationRestartIntegrationTest {
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired ValidationRestartService service;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean MonthlyValidationJobTrigger trigger;
  @MockitoBean MonthlyJobExecutionLookup lookup;
  Long run, user;
  CompletableFuture<JobExecution> pending;

  @BeforeEach
  void setup() {
    user = jdbc.queryForObject("SELECT min(user_id) FROM fgc.app_user", Long.class);
    run =
        jdbc.queryForObject(
            "INSERT INTO"
                + " fgc.validation_run(validation_month,run_no,run_type,status,current_step,triggered_by)"
                + " SELECT DATE '2093-07-01',COALESCE(MAX(run_no),0)+1,'MONTHLY','CREATED',0,? FROM"
                + " fgc.validation_run WHERE validation_month=DATE '2093-07-01' RETURNING"
                + " validation_run_id",
            Long.class,
            user);
    jdbc.update(
        "UPDATE fgc.validation_run SET status='RUNNING',current_step=1,started_at=clock_timestamp()"
            + " WHERE validation_run_id=?",
        run);
    jdbc.update(
        "UPDATE fgc.validation_run SET"
            + " status='FAILED',current_step=6,completed_at=clock_timestamp(),failure_message='시험"
            + " 실패' WHERE validation_run_id=?",
        run);
    var previous = new JobExecution(101L);
    previous.getExecutionContext().putLong("validationRunId", run);
    when(lookup.findFailedExecution(any())).thenReturn(previous);
    pending = new CompletableFuture<>();
    when(trigger.launchRestart(any(), anyString(), anyLong())).thenReturn(pending);
  }

  @AfterEach
  void releaseThisTestsPendingReservation() {
    pending.completeExceptionally(new IllegalStateException("test completed"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ACCEPTED", "LAUNCHING", "RUNNING"})
  void anotherFailedRunsPendingRestartReservesTheMonth(String state) {
    Long other =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status,triggered_by)
            SELECT validation_month,run_no+1,'MONTHLY','CREATED',triggered_by FROM fgc.validation_run
            WHERE validation_run_id=? RETURNING validation_run_id
            """,
            Long.class,
            run);
    jdbc.update(
        "UPDATE fgc.validation_run SET status='RUNNING',current_step=1,started_at=clock_timestamp()"
            + " WHERE validation_run_id=?",
        other);
    jdbc.update(
        "UPDATE fgc.validation_run SET"
            + " status='FAILED',current_step=6,completed_at=clock_timestamp(),failure_message='test'"
            + " WHERE validation_run_id=?",
        other);
    Long retry =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.validation_run_retry(validation_run_id,previous_batch_execution_id,failed_step,
                requested_by,idempotency_key,request_hash,reason,state)
            VALUES (?,101,6,?,'reserved',repeat('0',64),'test',?) RETURNING retry_id
            """,
            Long.class,
            other,
            user,
            state);
    try {
      var q = request();
      assertThat(service.eligibility(run).allowed()).isFalse();
      assertThatThrownBy(() -> service.restart(run, user, "other-reserved", q))
          .isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class);
      verify(trigger, never()).launchRestart(any(), anyString(), anyLong());
    } finally {
      jdbc.update(
          "UPDATE fgc.validation_run_retry SET state='FAILED',finished_at=clock_timestamp() WHERE"
              + " retry_id=?",
          retry);
    }
  }

  ValidationRestartRequest request() {
    var s = service.eligibility(run);
    return new ValidationRestartRequest(101L, s.expectedStateVersion(), "원인 수정");
  }

  /** VRUN-W02 실패 시각은 실행의 completed_at이 아니라 실패한 JobExecution.endTime에서 온다. */
  @Test
  void failedAtComesFromTheFailedBatchExecutionNotFromTheRun() {
    assertThat(service.eligibility(run).failedAt()).isNull();

    var endTime = java.time.LocalDateTime.of(2093, 7, 31, 23, 40);
    var failed = new JobExecution(101L);
    failed.getExecutionContext().putLong("validationRunId", run);
    failed.setEndTime(endTime);
    when(lookup.findFailedExecution(any())).thenReturn(failed);

    assertThat(service.eligibility(run).failedAt())
        .hasToString(
            com.susukkang.fgc.common.util.DateUtil.toSeoul(
                    endTime.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime())
                .toString());
  }

  /** 같은 순간을 UTC와 Asia/Seoul JVM의 벽시계 값으로 넣어도 응답은 같은 순간의 +09:00이다. */
  @Test
  void failedAtIsTheSameSeoulInstantWhicheverZoneTheBatchRanIn() {
    assertThat(
            ValidationRestartService.seoulInstant(
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0), java.time.ZoneId.of("UTC")))
        .hasToString("2026-01-01T09:00+09:00");
    assertThat(
            ValidationRestartService.seoulInstant(
                java.time.LocalDateTime.of(2026, 1, 1, 9, 0), java.time.ZoneId.of("Asia/Seoul")))
        .hasToString("2026-01-01T09:00+09:00");
  }

  /** 실행기가 스케줄러보다 먼저 만료 접수에 닿아도 오류 코드는 같다. */
  @Test
  void aRequestThatExpiredBeforeTheExecutorReachedItIsRecordedAsTimeout() {
    var a = service.restart(run, user, "late-executor", request());
    jdbc.update(
        "UPDATE fgc.validation_run_retry SET requested_at=clock_timestamp()-interval '6 minutes'"
            + " WHERE retry_id=?",
        a.retryId());
    pending.completeExceptionally(new IllegalStateException("만료되었거나 이미 기동된 재시작 접수입니다."));
    assertThat(service.history(run).getFirst().errorCode()).isEqualTo("LAUNCH_TIMEOUT");
  }

  /** 이미 기동을 시작한 작업의 실패는 오래됐어도 LAUNCH_FAILED다. */
  @Test
  void aLaunchThatAlreadyStartedStaysLaunchFailedEvenWhenOld() {
    var a = service.restart(run, user, "started-old", request());
    jdbc.update(
        "UPDATE fgc.validation_run_retry SET"
            + " state='LAUNCHING',requested_at=clock_timestamp()-interval '6 minutes' WHERE"
            + " retry_id=?",
        a.retryId());
    pending.completeExceptionally(new IllegalStateException("launch failed"));
    assertThat(service.history(run).getFirst().errorCode()).isEqualTo("LAUNCH_FAILED");
  }

  @Test
  void replayDoesNotLaunchAgainAndDifferentBodyIsRejected() {
    var q = request();
    var a = service.restart(run, user, "same", q);
    assertThat(service.restart(run, user, "same", q)).isEqualTo(a);
    assertThatThrownBy(
            () ->
                service.restart(
                    run,
                    user,
                    "same",
                    new ValidationRestartRequest(101L, q.expectedStateVersion(), "다른 사유")))
        .isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class);
    verify(trigger, times(1)).launchRestart(any(), anyString(), anyLong());
  }

  @Test
  void staleVersionAndMissingCheckpointDoNotCreateRetries() {
    assertThatThrownBy(
            () ->
                service.restart(
                    run, user, "stale", new ValidationRestartRequest(101L, "old", "수정")))
        .isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class);
    when(lookup.findFailedExecution(any())).thenThrow(new IllegalStateException("missing"));
    assertThat(service.eligibility(run).allowed()).isFalse();
    assertThat(service.history(run)).isEmpty();
  }

  @Test
  void asynchronousLaunchFailureIsDurablyFailed() {
    service.restart(run, user, "failure", request());
    pending.completeExceptionally(new IllegalStateException("launch failed"));
    assertThat(service.history(run).getFirst().state()).isEqualTo("FAILED");
    assertThat(service.history(run).getFirst().errorCode()).isEqualTo("LAUNCH_FAILED");
  }

  @Test
  void twoConcurrentRequestsAcceptExactlyOne() throws Exception {
    var q = request();
    var start = new CountDownLatch(1);
    java.util.function.Supplier<Boolean> call =
        () -> {
          var c =
              org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
          c.setAuthentication(
              new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                  "tester",
                  "",
                  List.of(
                      new org.springframework.security.core.authority.SimpleGrantedAuthority(
                          "ROLE_SETTLEMENT"))));
          org.springframework.security.core.context.SecurityContextHolder.setContext(c);
          try {
            start.await();
            service.restart(run, user, UUID.randomUUID().toString(), q);
            return true;
          } catch (com.susukkang.fgc.common.exception.FgcBusinessException e) {
            return false;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
          }
        };
    var a = CompletableFuture.supplyAsync(call);
    var b = CompletableFuture.supplyAsync(call);
    start.countDown();
    assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder(true, false);
    assertThat(service.history(run)).hasSize(1);
  }

  @Test
  @org.springframework.security.test.context.support.WithMockUser(roles = "COMPLIANCE")
  void readOnlyRoleCannotRestartThroughHttp() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/validation-runs/{id}/restart", run)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key", "denied")
                .contentType("application/json")
                .content(
                    "{\"expectedBatchExecutionId\":101,\"expectedStateVersion\":\"old\",\"reason\":\"수정\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    assertThat(service.history(run)).isEmpty();
  }

  @Test
  void blankReasonIsRejectedThroughHttp() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/validation-runs/{id}/restart", run)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key", "blank")
                .contentType("application/json")
                .content(
                    "{\"expectedBatchExecutionId\":101,\"expectedStateVersion\":\"old\",\"reason\":\"\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    assertThat(service.history(run)).isEmpty();
  }

  @Test
  void unstartedExpiredRequestDoesNotRemainPermanentlyAccepted() {
    var a = service.restart(run, user, "expired", request());
    jdbc.update(
        "UPDATE fgc.validation_run_retry SET requested_at=clock_timestamp()-interval '6 minutes'"
            + " WHERE retry_id=?",
        a.retryId());
    service.expireUnstartedRequests();
    assertThat(service.history(run).getFirst().state()).isEqualTo("FAILED");
    assertThat(service.history(run).getFirst().errorCode()).isEqualTo("LAUNCH_TIMEOUT");
  }

  @Test
  void rejectedExecutorRecordsFailureAndReturnsServiceUnavailable() {
    when(trigger.launchRestart(any(), anyString(), anyLong()))
        .thenThrow(new RejectedExecutionException("full"));
    var q = request();
    assertThatThrownBy(() -> service.restart(run, user, "rejected", q))
        .isInstanceOfSatisfying(
            com.susukkang.fgc.common.exception.FgcBusinessException.class,
            e ->
                assertThat(e.getErrorCode())
                    .isEqualTo(com.susukkang.fgc.common.exception.FgcErrorCode.VRUN_008));
    assertThat(service.history(run).getFirst().state()).isEqualTo("FAILED");
  }

  @Test
  void finalizedMonthDoesNotMasqueradeAsAnActiveRun() {
    Long other =
        jdbc.queryForObject(
            "INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status,triggered_by)"
                + " SELECT validation_month,run_no+1,'MONTHLY','CREATED',triggered_by FROM"
                + " fgc.validation_run WHERE validation_run_id=? RETURNING validation_run_id",
            Long.class,
            run);
    jdbc.update(
        "UPDATE fgc.validation_run SET status='RUNNING',current_step=1,started_at=clock_timestamp()"
            + " WHERE validation_run_id=?",
        other);
    jdbc.update(
        "UPDATE fgc.validation_run SET"
            + " status='COMPLETED',current_step=8,completed_at=clock_timestamp() WHERE"
            + " validation_run_id=?",
        other);
    jdbc.update(
        "UPDATE fgc.validation_run SET"
            + " status='FINALIZED',current_step=10,finalized_at=clock_timestamp(),finalized_by=?"
            + " WHERE validation_run_id=?",
        user,
        other);
    assertThat(service.eligibility(run).allowed()).isTrue();
  }

  @Test
  void anotherActiveMonthlyRunBlocksRestart() {
    jdbc.update(
        "INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status,triggered_by)"
            + " SELECT validation_month,run_no+1,'MONTHLY','CREATED',triggered_by FROM"
            + " fgc.validation_run WHERE validation_run_id=?",
        run);
    assertThat(service.eligibility(run).allowed()).isFalse();
    jdbc.update(
        "UPDATE fgc.validation_run SET status='RUNNING',current_step=1,started_at=clock_timestamp()"
            + " WHERE validation_month=DATE '2093-07-01' AND status='CREATED'");
    jdbc.update(
        "UPDATE fgc.validation_run SET"
            + " status='FAILED',completed_at=clock_timestamp(),failure_message='시험 종료' WHERE"
            + " validation_month=DATE '2093-07-01' AND status='RUNNING'");
  }
}
