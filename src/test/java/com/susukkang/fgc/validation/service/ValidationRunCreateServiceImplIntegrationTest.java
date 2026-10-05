package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidationRunCreateServiceImpl을 실제 DB·실제 트랜잭션 매니저로 동시에 두 번 호출해
 * ValidationRunCreateServiceImplTest(mock 기반)로는 증명할 수 없는 세 가지 경합 시나리오를
 * 검증한다:
 *   1) 같은 달 MONTHLY 두 건이 동시에 들어오면 하나만 성공하고 하나는 VRUN_001이어야 한다
 *      (재시도로 해결되면 안 된다 — uq_validation_run_active_month는 run_no를 바꿔도 위반).
 *   2) 활성-실행-1건 제약이 없는 runType(PRE_CONFIRM) 두 건이 동시에 들어오면 run_no 경합이
 *      나더라도 재시도로 둘 다 성공해야 한다(서로 다른 run_no를 받아야 한다).
 *   3) 같은 MANUAL_CONTRACT 두 건이 동시에 들어오면 (1)과 마찬가지로 하나만 성공하고 하나는
 *      VRUN_001이어야 한다 — uq_validation_run_active_manual_contract(V13, 코드리뷰로 발견된
 *      "하루 1건" 멱등성이 DB 제약이 아니었던 문제의 수정) 반영.
 *
 * @Transactional 테스트 롤백을 안 쓴다 — 여러 스레드가 각자 다른 트랜잭션/커넥션으로 실제
 * 커밋해야 경합이 재현되므로, 테스트 종료 후 jdbcTemplate으로 직접 정리한다.
 */
@SpringBootTest
class ValidationRunCreateServiceImplIntegrationTest {

    private static final LocalDate MONTHLY_MONTH = LocalDate.of(2095, 1, 1);
    private static final LocalDate NON_MONTHLY_MONTH = LocalDate.of(2095, 2, 1);

    // 배치 테스트가 무작위로 같은 월을 사용할 수 있으므로, 생성에 성공한 ID만 정리한다.
    // 각 작업 스레드에서 커밋 직후 기록해 assertion 실패 시에도 정리할 수 있게 한다.
    private final ConcurrentLinkedQueue<Long> createdValidationRunIds = new ConcurrentLinkedQueue<>();

    @Autowired
    private ValidationRunCreateService validationRunCreateService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        createdValidationRunIds.forEach(id ->
                jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", id));
        createdValidationRunIds.clear();
    }

    private Callable<ValidationRunRow> createTask(LocalDate month, ValidationRunType runType) {
        CreateValidationRunCommand command = new CreateValidationRunCommand(month, runType, null);
        return () -> {
            ValidationRunRow row = validationRunCreateService.create(command);
            createdValidationRunIds.add(row.getValidationRunId());
            return row;
        };
    }

    @Test
    void cleanUpDeletesOnlyOwnedRunsWhenAnotherRunInTheSameMonthHasAnException() throws Exception {
        // 일 배치가 같은 월을 무작위로 선택하고 예외 이력을 남기는 CI 상황을 재현한다.
        Long otherRunId = validationRunCreateService.create(
                new CreateValidationRunCommand(NON_MONTHLY_MONTH, ValidationRunType.PRE_CONFIRM, null))
                .getValidationRunId();
        try {
            Long otherCaseId = jdbcTemplate.queryForObject("""
                    INSERT INTO fgc.exception_case
                        (exception_key, exception_type, severity, validation_run_id,
                         source_entity_type, source_entity_id, title)
                    VALUES (?, 'DATA_QUALITY', 'WARNING', ?, 'IT', ?, '테스트 데이터 정리 격리 검증')
                    RETURNING exception_case_id
                    """, Long.class, "IT-CLEANUP:" + UUID.randomUUID(), otherRunId, otherRunId.toString());
            Long ownedRunId = createTask(NON_MONTHLY_MONTH, ValidationRunType.MANUAL_CONTRACT)
                    .call().getValidationRunId();

            cleanUp();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                    Long.class, ownedRunId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                    Long.class, otherRunId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.exception_case WHERE exception_case_id = ?",
                    Long.class, otherCaseId)).isEqualTo(1);

            // 활성 MANUAL_CONTRACT가 남아 후속 테스트의 생성을 막지 않아야 한다.
            assertThat(createTask(NON_MONTHLY_MONTH, ValidationRunType.MANUAL_CONTRACT)
                    .call().getValidationRunId()).isNotEqualTo(ownedRunId);
        } finally {
            jdbcTemplate.update("DELETE FROM fgc.exception_case WHERE validation_run_id = ?", otherRunId);
            jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", otherRunId);
        }
    }

    @Test
    void concurrentMonthlyRunsResultInExactlyOneSuccessAndOneAlreadyRunningConflict() throws InterruptedException {
        LocalDate month = MONTHLY_MONTH;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ValidationRunRow>> futures = executor.invokeAll(List.of(
                    createTask(month, ValidationRunType.MONTHLY),
                    createTask(month, ValidationRunType.MONTHLY)
            ));

            int successCount = 0;
            int conflictCount = 0;
            for (Future<ValidationRunRow> future : futures) {
                try {
                    ValidationRunRow row = future.get(10, TimeUnit.SECONDS);
                    assertThat(row.getStatus()).isEqualTo("CREATED");
                    successCount++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(FgcBusinessException.class);
                    assertThat(((FgcBusinessException) e.getCause()).getErrorCode()).isEqualTo(FgcErrorCode.VRUN_001);
                    conflictCount++;
                } catch (TimeoutException e) {
                    throw new AssertionError("작업이 제한 시간 안에 끝나지 않았다", e);
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrentPreConfirmRunsBothSucceedWithDistinctRunNo() throws InterruptedException {
        // PRE_CONFIRM은 활성-실행-1건 제약이 없는 runType이라, run_no 채번 경합만 재시도로
        // 풀리면 둘 다 성공해야 한다. MANUAL_CONTRACT는 V9부터 이 시나리오가 성립하지 않으므로
        // (아래 concurrentManualContractRunsResultInExactlyOneSuccessAndOneActiveConflict 참고)
        // PRE_CONFIRM으로 이 케이스를 대신 검증한다.
        LocalDate month = NON_MONTHLY_MONTH;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ValidationRunRow>> futures = executor.invokeAll(List.of(
                    createTask(month, ValidationRunType.PRE_CONFIRM),
                    createTask(month, ValidationRunType.PRE_CONFIRM)
            ));

            List<ValidationRunRow> rows = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError("동시 생성 요청이 실패하면 안 된다(재시도로 해결돼야 함)", e);
                        }
                    })
                    .collect(Collectors.toList());

            Set<Integer> runNos = rows.stream().map(ValidationRunRow::getRunNo).collect(Collectors.toSet());
            assertThat(rows).hasSize(2);
            assertThat(runNos).hasSize(2);
        } finally {
            executor.shutdown();
        }
    }

    // #76 코드리뷰로 발견: docs/05_인터페이스정의서_v2_0.md:536이 "기존 UNIQUE 제약이 그대로
    // 작동"한다고 설명하지만 실제로는 MANUAL_CONTRACT를 막는 제약이 없었다. V9 마이그레이션이
    // uq_validation_run_active_manual_contract를 추가했고, existsActiveManualContractRun
    // 사전 확인이 MONTHLY와 같은 패턴으로 이를 FgcBusinessException(VRUN_001)으로 앞서 걸러낸다.
    @Test
    void concurrentManualContractRunsResultInExactlyOneSuccessAndOneActiveConflict() throws InterruptedException {
        LocalDate month = NON_MONTHLY_MONTH;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ValidationRunRow>> futures = executor.invokeAll(List.of(
                    createTask(month, ValidationRunType.MANUAL_CONTRACT),
                    createTask(month, ValidationRunType.MANUAL_CONTRACT)
            ));

            int successCount = 0;
            int conflictCount = 0;
            for (Future<ValidationRunRow> future : futures) {
                try {
                    ValidationRunRow row = future.get(10, TimeUnit.SECONDS);
                    assertThat(row.getStatus()).isEqualTo("CREATED");
                    successCount++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(FgcBusinessException.class);
                    assertThat(((FgcBusinessException) e.getCause()).getErrorCode()).isEqualTo(FgcErrorCode.VRUN_001);
                    conflictCount++;
                } catch (TimeoutException e) {
                    throw new AssertionError("작업이 제한 시간 안에 끝나지 않았다", e);
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
