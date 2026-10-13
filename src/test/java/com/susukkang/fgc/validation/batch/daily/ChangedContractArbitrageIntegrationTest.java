package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.arbitrage.service.ArbitrageCheckBatchItemService;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.validation.repository.ContractStatusEventProcessingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;

/**
 * #512 — changedContractStep의 Processor가 해지 계약의 차익거래를 효력일 기준으로 저장하고,
 * 같은 실행으로 다시 돌려도 결과행·예외가 늘지 않는지 실제 PostgreSQL로 확인한다.
 * 시드 A1(FGC-FGL02-202601-0001): 8/20 해지, 지급 890,000 + 해약환급금 120,000 − 납입 800,000 = 210,000 → CANDIDATE.
 * A1은 GA→FC 1,200% 룰셋이 없는(CAP_004) 계약이라, 그 단계가 실패가 아니라 검토 큐로만 남고(#522)
 * 상태 사건이 SUCCEEDED로 끝나 다음 날 Reader 후보에서 빠지는지도 함께 본다.
 *
 * 스케줄 재생성은 이 테스트의 관심사가 아니고 시드 계약의 공유 스케줄을 바꾸므로 mock으로 둔다.
 * 검토 큐 등록(registerCapRuleReview)만 실제 빈에 위임한다. 차익거래·1,200%·Writer는 실제 빈을 쓴다.
 *
 * Processor는 실제 Step처럼 청크 트랜잭션 안에서 부른다. 1,200%는 청크 트랜잭션에서 계약 행을
 * FOR UPDATE로 잠그는데(CapCheckServiceImpl), 그 뒤에 REQUIRES_NEW(다른 연결)로 arbitrage_check를
 * 넣으면 FK 검사(FOR KEY SHARE)가 그 잠금을 기다리고 청크 스레드는 차익거래를 기다려 끝없이 멈춘다.
 * PostgreSQL은 대기자가 하나뿐이라 교착으로 감지하지 못한다 — 순서가 바뀌면 @Timeout으로 실패한다.
 */
@SpringBootTest
class ChangedContractArbitrageIntegrationTest {

    private static final String A1_CONTRACT_NO = "FGC-FGL02-202601-0001";

    @Autowired
    private InsuranceContractRepository insuranceContractRepository;
    @Autowired
    private CapCheckService capCheckService;
    @Autowired
    private ContractStatusEventProcessingRepository contractStatusEventProcessingRepository;
    @Autowired
    private ArbitrageCheckBatchItemService arbitrageCheckBatchItemService;
    @Autowired
    private ContractStatusEventRepository contractStatusEventRepository;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ExceptionCaseRepository exceptionCaseRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ChangedContractItemProcessor processor;
    private ChangedContractItemWriter writer;
    private Long validationRunId;
    private Long contractId;

    @BeforeEach
    void setUp() {
        ScheduleService scheduleServiceWithRealReview = mock(ScheduleService.class);
        willAnswer(invocation -> {
            scheduleService.registerCapRuleReview(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).given(scheduleServiceWithRealReview).registerCapRuleReview(any(), any(), any());
        processor = new ChangedContractItemProcessor(
                insuranceContractRepository, scheduleServiceWithRealReview, capCheckService,
                contractStatusEventProcessingRepository, arbitrageCheckBatchItemService, contractStatusEventRepository);
        writer = new ChangedContractItemWriter(contractStatusEventProcessingRepository, exceptionCaseRepository);

        // exception_occurrence가 append-only라 이 실행은 정리하지 못하고 남는다 — 다른 테스트의
        // 예외 키(검증월 포함)·uq_validation_run과 겹치지 않도록 먼 미래의 무작위 월을 쓴다.
        LocalDate month = LocalDate.of(2040 + ThreadLocalRandom.current().nextInt(60),
                1 + ThreadLocalRandom.current().nextInt(12), 1);
        validationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MANUAL_CONTRACT', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, month, month);
        contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?", Long.class, A1_CONTRACT_NO);

        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, DailyChangedContractJobNames.JOB_NAME), new JobParameters());
        jobExecution.getExecutionContext().putLong("validationRunId", validationRunId);
        jobExecution.getExecutionContext().putString("lastProcessedAt", "2026-08-21T02:00:00+09:00");
        StepExecution stepExecution = new StepExecution("changedContractStep", jobExecution);
        processor.beforeStep(stepExecution);
        writer.beforeStep(stepExecution);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void terminatedContractGetsArbitrageRowAsOfEffectiveDateAndRerunAddsNothing() {
        ChangedContractResult first = processInChunkTransaction();

        // A1은 2026-01 계약이라 GA→FC 1,200% 룰셋이 없다(CAP_004) — 그 단계는 1,200% 대상이 아니라
        // 실패가 아니다(#522). INSURER→GA는 룰셋이 있어 계산되고, 차익거래도 점검된다.
        assertThat(first.success()).isTrue();
        assertThat(capCheckStages()).containsExactly("INSURER_TO_GA");
        assertThat(policyMissingReviews()).isEqualTo(1);
        assertThat(revalidationFailures()).isZero();
        assertThat(failedProcessingRows()).isZero();
        // 상태 사건이 SUCCEEDED로 끝나 Reader의 NOT EXISTS(...SUCCEEDED) 조건에서 빠진다 — 다음 날 다시 읽지 않는다.
        assertThat(contractStatusEventProcessingRepository.findPendingEventIds(
                contractId, DailyChangedContractJobNames.JOB_NAME)).isEmpty();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT as_of_date, result_status,
                       (calculation_snapshot ->> 'currentExcessAmount')::numeric AS current_excess
                  FROM fgc.arbitrage_check
                 WHERE validation_run_id = ? AND contract_id = ?
                """, validationRunId, contractId);
        assertThat(row.get("as_of_date").toString()).isEqualTo("2026-08-20");
        assertThat(row.get("result_status")).isEqualTo("CANDIDATE");
        assertThat(row.get("current_excess")).isEqualTo(new java.math.BigDecimal("210000"));
        assertThat(arbitrageRows()).isEqualTo(1);
        assertThat(candidateCases()).isEqualTo(1);
        assertThat(candidateOccurrences()).isEqualTo(1);

        // 같은 날 같은 실행으로 다시 돌려도(IF-BAT-02 멱등성) uq_arbitrage_check의 UPSERT와
        // 예외 업무키·실행별 occurrence 멱등 기록 덕분에 행이 늘지 않는다.
        // 검토 건도 계약·단계·유형 업무키 UPSERT라 늘지 않는다.
        ChangedContractResult second = processInChunkTransaction();

        assertThat(second.success()).isTrue();
        assertThat(arbitrageRows()).isEqualTo(1);
        assertThat(candidateCases()).isEqualTo(1);
        assertThat(candidateOccurrences()).isEqualTo(1);
        assertThat(policyMissingReviews()).isEqualTo(1);
        assertThat(revalidationFailures()).isZero();
        assertThat(failedProcessingRows()).isZero();
    }

    private ChangedContractResult processInChunkTransaction() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ChangedContractResult result = processor.process(contractId);
            writer.write(new Chunk<>(List.of(result)));
            return result;
        });
    }

    private List<String> capCheckStages() {
        return jdbcTemplate.queryForList(
                "SELECT payment_stage FROM fgc.cap_check WHERE validation_run_id = ? AND contract_id = ?",
                String.class, validationRunId, contractId);
    }

    private Integer policyMissingReviews() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_case WHERE exception_key = ? AND status = 'NEW'",
                Integer.class, "SCHEDULE_POLICY:" + contractId + ":GA_TO_FC:POLICY_MISSING");
    }

    private Integer revalidationFailures() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_occurrence o
                  JOIN fgc.exception_case c ON c.exception_case_id = o.exception_case_id
                 WHERE c.contract_id = ? AND c.reason_code = 'CONTRACT_REVALIDATION_FAILED'
                   AND o.validation_run_id = ?
                """, Integer.class, contractId, validationRunId);
    }

    private Integer failedProcessingRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.contract_status_event_processing WHERE validation_run_id = ? AND processing_status = 'FAILED'",
                Integer.class, validationRunId);
    }

    private Integer arbitrageRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.arbitrage_check WHERE validation_run_id = ? AND contract_id = ?",
                Integer.class, validationRunId, contractId);
    }

    private Integer candidateCases() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_case
                 WHERE contract_id = ? AND exception_type = 'ARBITRAGE_CANDIDATE'
                   AND first_detected_run_id = ?
                """, Integer.class, contractId, validationRunId);
    }

    private Integer candidateOccurrences() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_occurrence o
                  JOIN fgc.exception_case c ON c.exception_case_id = o.exception_case_id
                 WHERE c.contract_id = ? AND c.exception_type = 'ARBITRAGE_CANDIDATE'
                   AND o.validation_run_id = ?
                """, Integer.class, contractId, validationRunId);
    }
}
