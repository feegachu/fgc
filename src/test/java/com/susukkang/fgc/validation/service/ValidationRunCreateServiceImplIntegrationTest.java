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
import java.util.concurrent.Callable;
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
 * ValidationRunCreateServiceImplTest(mock 기반)로는 증명할 수 없는 두 가지 경합 시나리오를
 * 검증한다:
 *   1) 같은 달 MONTHLY 두 건이 동시에 들어오면 하나만 성공하고 하나는 VRUN_001이어야 한다
 *      (재시도로 해결되면 안 된다 — uq_validation_run_active_month는 run_no를 바꿔도 위반).
 *   2) 같은 달 비-MONTHLY 두 건이 동시에 들어오면 run_no 경합이 나더라도 재시도로 둘 다
 *      성공해야 한다(서로 다른 run_no를 받아야 한다).
 *
 * @Transactional 테스트 롤백을 안 쓴다 — 여러 스레드가 각자 다른 트랜잭션/커넥션으로 실제
 * 커밋해야 경합이 재현되므로, 테스트 종료 후 jdbcTemplate으로 직접 정리한다.
 */
@SpringBootTest
class ValidationRunCreateServiceImplIntegrationTest {

    @Autowired
    private ValidationRunCreateService validationRunCreateService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_month IN (?, ?)",
                LocalDate.of(2099, 1, 1), LocalDate.of(2099, 2, 1));
    }

    private Callable<ValidationRunRow> createTask(LocalDate month, ValidationRunType runType) {
        CreateValidationRunCommand command = new CreateValidationRunCommand(month, runType, null);
        return () -> validationRunCreateService.create(command);
    }

    @Test
    void concurrentMonthlyRunsResultInExactlyOneSuccessAndOneAlreadyRunningConflict() throws InterruptedException {
        LocalDate month = LocalDate.of(2099, 1, 1);
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
    void concurrentNonMonthlyRunsBothSucceedWithDistinctRunNo() throws InterruptedException {
        LocalDate month = LocalDate.of(2099, 2, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ValidationRunRow>> futures = executor.invokeAll(List.of(
                    createTask(month, ValidationRunType.MANUAL_CONTRACT),
                    createTask(month, ValidationRunType.MANUAL_CONTRACT)
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
}
