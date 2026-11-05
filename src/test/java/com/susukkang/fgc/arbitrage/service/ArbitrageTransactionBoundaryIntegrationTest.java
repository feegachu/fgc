package com.susukkang.fgc.arbitrage.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseDetectionCommandsImpl;
import com.susukkang.fgc.validation.service.ValidationRunExecuteService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 커밋 경계는 테스트 전용 DB에서 검증한다. 개발 DB나 공유 테스트 DB에 이력을 남기지 않는다. */
@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_arbitrage_boundary?currentSchema=fgc")
class ArbitrageTransactionBoundaryIntegrationTest {
  @Autowired ArbitrageCheckBatchItemService itemService;
  @Autowired ArbitrageService service;
  @Autowired ValidationRunExecuteService executeService;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoSpyBean ExceptionCaseDetectionCommandsImpl exceptionRepository;
  @MockitoSpyBean com.susukkang.fgc.arbitrage.repository.ArbitrageCheckQueriesImpl queries;

  @MockitoSpyBean AuditLogService auditLogService;

  @Test
  void rollsBackPartialDetectionRetriesWithoutDuplicatesAndCommitsOtherContractIndependently() {
    List<Reference> references =
        jdbc.query(
            "SELECT contract_id, contract_date FROM fgc.insurance_contract WHERE contract_date >="
                + " DATE '2026-03-01' ORDER BY contract_id LIMIT 2",
            (rs, i) -> new Reference(rs.getLong(1), LocalDate.of(1900, 1, 1)));
    assertThat(references).hasSize(2);
    Long runId =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
            VALUES (DATE '2094-01-01', 1, 'PRE_CONFIRM', 'CREATED') RETURNING validation_run_id
            """,
            Long.class);
    Reference first = references.getFirst();
    ExceptionCaseDetectionCommandsImpl spyTarget =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(exceptionRepository);
    doAnswer(
            invocation -> {
              invocation.callRealMethod(); // 계산 결과와 예외·occurrence까지 쓴 다음 실패시킨다.
              throw new FgcBusinessException(FgcErrorCode.COMMON_500, Map.of());
            })
        .when(spyTarget)
        .insertArbitrageReviewCase(
            anyString(),
            eq(runId),
            eq(first.id()),
            anyLong(),
            anyString(),
            anyString(),
            anyString());
    Long failedContractId = first.id();
    LocalDate failedAsOfDate = first.date();
    assertThatThrownBy(() -> itemService.process(runId, failedContractId, failedAsOfDate))
        .isInstanceOf(FgcBusinessException.class);
    assertThat(count("arbitrage_check", runId)).isZero();
    assertThat(count("exception_occurrence", runId)).isZero();
    assertThat(count("exception_case", runId)).isZero();
    reset(spyTarget);

    Reference second = references.get(1);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              itemService.process(runId, second.id(), second.date());
              status.setRollbackOnly(); // 바깥 청크가 롤백돼도 REQUIRES_NEW 성공 계약은 남는다.
            });
    assertThat(count("arbitrage_check", runId)).isEqualTo(1);
    var retried = itemService.process(runId, first.id(), first.date());
    var repeated = itemService.process(runId, first.id(), first.date());
    assertThat(repeated.getArbitrageCheckId()).isEqualTo(retried.getArbitrageCheckId());
    assertThat(count("arbitrage_check", runId)).isEqualTo(2);
    assertThat(count("exception_occurrence", runId)).isEqualTo(2);
    assertThat(count("exception_case", runId)).isEqualTo(2);
  }

  @Test
  void manualCheckCreatesPolicySnapshotCompletesRunAndRecordsAudit() {
    Reference reference =
        jdbc.queryForObject(
            "SELECT contract_id, contract_date FROM fgc.insurance_contract ORDER BY contract_id"
                + " LIMIT 1",
            (rs, i) -> new Reference(rs.getLong(1), LocalDate.of(1900, 1, 1)));
    Long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    var result =
        service.reArbitrageCheck(
            reference.id(), new ReArbitrageCheckRequest(reference.date(), "JPA 수동 검증 연계"), userId);
    var run =
        jdbc.queryForMap(
            "SELECT run_type, status, current_step, policy_snapshot FROM fgc.validation_run WHERE"
                + " validation_run_id = ?",
            result.getValidationRunId());
    assertThat(run)
        .containsEntry("run_type", "MANUAL_CONTRACT")
        .containsEntry("status", "COMPLETED")
        .containsEntry("current_step", 8);
    assertThat(run.get("policy_snapshot").toString())
        .contains("capRuleSets", "refundRateTables", "productOfferings");
    assertThat(result.getArbitrageCheckId()).isNotNull();
    assertThat(count("arbitrage_check", result.getValidationRunId())).isEqualTo(1);
    assertThat(count("exception_occurrence", result.getValidationRunId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM fgc.audit_log WHERE action_code = 'ARBITRAGE_RECHECKED' AND"
                    + " entity_id = ?",
                Long.class,
                result.getArbitrageCheckId().toString()))
        .isEqualTo(1);
  }

  @Test
  void correctionCommittedAfterBalanceReadCannotMixOldPaidWithNewRemaining() throws Exception {
    var columns =
        jdbc.queryForList(
            """
            SELECT column_name FROM information_schema.columns WHERE table_schema='fgc'
              AND table_name='insurance_contract' AND is_identity='NO' AND is_generated='NEVER'
            ORDER BY ordinal_position
            """,
            String.class);
    String select =
        columns.stream()
            .map(
                c ->
                    c.equals("contract_no")
                        ? "'IT-754-SNAPSHOT-" + java.util.UUID.randomUUID() + "'"
                        : c)
            .collect(java.util.stream.Collectors.joining(","));
    Long contract =
        jdbc.queryForObject(
            "INSERT INTO fgc.insurance_contract ("
                + String.join(",", columns)
                + ") SELECT "
                + select
                + " FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id",
            Long.class);
    Long header =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,
                schedule_purpose,schedule_regime)
            VALUES (?,'GA_TO_FC',(SELECT min(policy_version_id) FROM fgc.policy_version),1,'OPERATIONAL','CURRENT')
            RETURNING schedule_header_id
            """,
            Long.class,
            contract);
    Long line =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.schedule_line(schedule_header_id,line_no,installment_no,contract_month_no,due_date,
                commission_item_id,beneficiary_agent_id,basis_code,basis_amount,calculation_type,fixed_amount,expected_amount)
            SELECT ?,1,1,1,DATE '2030-08-15',(SELECT min(commission_item_id) FROM fgc.commission_item),agent_id,
                'TEST',100000,'FIXED',100000,100000 FROM fgc.insurance_contract WHERE contract_id=? RETURNING schedule_line_id
            """,
            Long.class,
            header,
            contract);
    Long payment =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,source_contract_id,
                recipient_agent_id,commission_item_id,installment_no,settlement_month,due_date,amount,cashflow_type,status)
            SELECT 'GA_TO_FC','GA_MANUAL_PAYMENT',?, ?,beneficiary_agent_id,commission_item_id,1,
                DATE '2030-08-01',due_date,50000,'PAYMENT','DRAFT' FROM fgc.schedule_line WHERE schedule_line_id=?
            RETURNING commission_transaction_id
            """,
            Long.class,
            "IT-754-SNAPSHOT:" + java.util.UUID.randomUUID(),
            contract,
            line);
    jdbc.update(
        """
        INSERT INTO fgc.transaction_attribution(commission_transaction_id,attribution_seq,attribution_scope,
            contract_id,schedule_line_id,attribution_date,attribution_month,attributed_amount,
            inclusion_status_snapshot,attribution_method)
        VALUES (?,1,'CONTRACT',?,?,DATE '2030-08-15',DATE '2030-08-01',50000,'INCLUDED','DIRECT')
        """,
        payment,
        contract,
        line);
    jdbc.update(
        "UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE"
            + " commission_transaction_id=?",
        payment);
    Long run =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status)
            VALUES (DATE '2094-03-01',1,'PRE_CONFIRM','CREATED') RETURNING validation_run_id
            """,
            Long.class);
    var target =
        org.springframework.test.util.AopTestUtils
            .<com.susukkang.fgc.arbitrage.repository.ArbitrageCheckQueriesImpl>
                getUltimateTargetObject(queries);
    doAnswer(
            invocation -> {
              Object paid = invocation.callRealMethod();
              assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class))
                  .isEqualTo("read committed");
              // A distinct transaction commits cancellation after the atomic balance read, before
              // persistence.
              java.util.concurrent.CompletableFuture.runAsync(
                      () ->
                          new TransactionTemplate(transactionManager)
                              .executeWithoutResult(
                                  tx ->
                                      jdbc.update(
                                          "UPDATE fgc.commission_transaction SET status='CANCELLED'"
                                              + " WHERE commission_transaction_id=?",
                                          payment)))
                  .get(20, java.util.concurrent.TimeUnit.SECONDS);
              return paid;
            })
        .when(target)
        .commissionBalanceSnapshot(eq(contract), any(), any());
    try {
      var before = itemService.process(run, contract, LocalDate.of(2030, 8, 31));
      assertThat(before.getPaidCommissionAmount()).isEqualByComparingTo("50000");
      assertThat(before.getPlannedCommissionAmount()).isEqualByComparingTo("50000");
    } finally {
      reset(target);
    }
    var after = itemService.process(run, contract, LocalDate.of(2030, 8, 31));
    assertThat(after.getPaidCommissionAmount()).isEqualByComparingTo("0");
    assertThat(after.getPlannedCommissionAmount()).isEqualByComparingTo("100000");
  }

  @Test
  void unknownContractIsRejectedWithoutCreatingARun() {
    Long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM fgc.validation_run", Long.class);
    assertThatThrownBy(
            () ->
                service.reArbitrageCheck(
                    999_999_999L,
                    new ReArbitrageCheckRequest(LocalDate.of(1900, 1, 1), "없는 계약"),
                    userId))
        .isInstanceOfSatisfying(
            FgcBusinessException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.validation_run", Long.class))
        .isEqualTo(before);
  }

  /** 활성 수동 실행은 전역 1건이다(V13). 실패가 실행을 남기면 다른 계약의 수동 검증과 다음 날 일 배치까지 막힌다. */
  @Test
  void failedManualCheckRemovesItsRunSoTheNextCheckIsNotBlocked() {
    Reference reference =
        jdbc.queryForObject(
            "SELECT contract_id, contract_date FROM fgc.insurance_contract ORDER BY contract_id"
                + " LIMIT 1",
            (rs, i) -> new Reference(rs.getLong(1), LocalDate.of(1900, 1, 1)));
    Long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM fgc.validation_run", Long.class);
    var request = new ReArbitrageCheckRequest(reference.date(), "실패 뒤 다시 검증");
    doThrow(new FgcBusinessException(FgcErrorCode.COMMON_500, Map.of()))
        .when(auditLogService)
        .record(any());
    Long contractId = reference.id();
    try {
      assertThatThrownBy(() -> service.reArbitrageCheck(contractId, request, userId))
          .isInstanceOf(FgcBusinessException.class);
    } finally {
      reset(auditLogService);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.validation_run", Long.class))
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_run WHERE run_type = 'MANUAL_CONTRACT' AND"
                    + " status IN ('CREATED', 'RUNNING')",
                Long.class))
        .isZero();
    assertThat(service.reArbitrageCheck(reference.id(), request, userId).getResultStatus())
        .isNotNull();
  }

  /** 이 수정 전에 남은 행은 트리거가 CREATED→FAILED를 막아 Runbook 6-3절 SQL로 못 푼다. 6-5절의 [실행]으로 풀린다. */
  @Test
  void rowLeftBeforeThisFixIsClearedByExecuteSoTheNextCheckIsNotBlocked() {
    Reference reference =
        jdbc.queryForObject(
            "SELECT contract_id, contract_date FROM fgc.insurance_contract ORDER BY contract_id"
                + " LIMIT 1",
            (rs, i) -> new Reference(rs.getLong(1), LocalDate.of(1900, 1, 1)));
    Long userId =
        jdbc.queryForObject(
            "SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
    Long leftRun =
        jdbc.queryForObject(
            """
            INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status, triggered_by, created_at)
            VALUES (DATE '2094-02-01', 1, 'MANUAL_CONTRACT', 'CREATED', ?, now() - interval '3 days') RETURNING validation_run_id
            """,
            Long.class,
            userId);
    var request = new ReArbitrageCheckRequest(reference.date(), "남은 행을 푼 뒤 다시 검증");

    assertThatThrownBy(() -> service.reArbitrageCheck(reference.id(), request, userId))
        .isInstanceOfSatisfying(
            FgcBusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_001));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE fgc.validation_run SET status = 'FAILED' WHERE validation_run_id = ?",
                    leftRun))
        .isInstanceOf(DataAccessException.class);

    executeService.execute(leftRun, userId, "runbook-6-5");

    await()
        .atMost(Duration.ofMinutes(2))
        .until(() -> List.of("COMPLETED", "FAILED").contains(runStatus(leftRun)));
    assertThat(runStatus(leftRun)).isEqualTo("COMPLETED");
    assertThat(service.reArbitrageCheck(reference.id(), request, userId).getResultStatus())
        .isNotNull();
  }

  private String runStatus(Long runId) {
    return jdbc.queryForObject(
        "SELECT status FROM fgc.validation_run WHERE validation_run_id = ?", String.class, runId);
  }

  private long count(String table, Long runId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM fgc." + table + " WHERE validation_run_id = ?", Long.class, runId);
  }

  private record Reference(Long id, LocalDate date) {}
}
