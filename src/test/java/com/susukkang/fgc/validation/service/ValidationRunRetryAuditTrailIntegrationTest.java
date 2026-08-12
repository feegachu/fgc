package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #77 "재실행 시 triggeredBy, requestId, 실행 번호, 실패·재시작 이력이 감사 로그에 남는지
 * 검증". 실제 DB로 start → fail → retry(transition+recordRetried) 순서를 그대로 밟아,
 * audit_log에 각 단계가 실제로 구분 가능한 행으로 남는지 확인한다.
 *
 * 이 테스트가 잡아내는 회귀: ValidationRunTransitionServiceImpl.transition()은 감사로그를
 * 남기지 않는다(범용 상태 전이라 batch 전용 lifecycleService를 거치지 않음) — 그래서
 * CreateDailyRunTasklet의 FAILED→RUNNING 재시도 분기가 auditService.recordRetried()를
 * 직접 부르지 않으면, 재시도 자체가 감사 로그에서 완전히 사라진다.
 *
 * @Transactional로 테스트 종료 시 자동 롤백시킨다 — audit_log는 append-only라 명시적
 * DELETE로 정리할 수 없었지만(코드리뷰 반영), 롤백은 트리거를 거치지 않고 트랜잭션 전체를
 * 되돌리므로 이 테스트가 남기는 audit_log 행도 함께 정리된다.
 */
@SpringBootTest
@Transactional
class ValidationRunRetryAuditTrailIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2031, 6, 1);

    @Autowired
    private ValidationRunBatchLifecycleService lifecycleService;
    @Autowired
    private ValidationRunBatchAuditService auditService;
    @Autowired
    private ValidationRunTransitionService validationRunTransitionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long insertCreatedRun(int runNo) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, 'MANUAL_CONTRACT', 'CREATED')
                RETURNING validation_run_id
                """, Long.class, TEST_MONTH, runNo);
    }

    private List<Map<String, Object>> auditRowsFor(Long validationRunId) {
        return jdbcTemplate.queryForList("""
                SELECT action_code, user_id, request_id
                  FROM fgc.audit_log
                 WHERE entity_type = 'VALIDATION_RUN' AND entity_id = ?
                 ORDER BY audit_log_id
                """, String.valueOf(validationRunId));
    }

    @Test
    void startFailAndRetrySequenceLeavesADistinguishableAuditTrail() {
        int runNo = (int) (System.nanoTime() % 100000);
        Long id = insertCreatedRun(runNo);

        MonthlyValidationJobParameters firstAttempt = new MonthlyValidationJobParameters(
                TEST_MONTH, (long) runNo, ValidationRunType.MANUAL_CONTRACT, 3L, "request-first-attempt");
        MonthlyValidationJobParameters retryAttempt = new MonthlyValidationJobParameters(
                TEST_MONTH, (long) runNo, ValidationRunType.MANUAL_CONTRACT, 3L, "request-retry-attempt");

        // 1) 최초 시작
        lifecycleService.start(id, firstAttempt);
        // 2) 도중 실패
        lifecycleService.fail(id, 1, firstAttempt, "changedContractStep 실패(테스트)");
        // 3) 재시도 — CreateDailyRunTasklet의 FAILED 분기와 동일한 순서
        validationRunTransitionService.transition(id, ValidationRunStatus.RUNNING);
        auditService.recordRetried(id, retryAttempt);

        List<Map<String, Object>> rows = auditRowsFor(id);
        assertThat(rows).extracting(r -> r.get("action_code"))
                .containsExactly("VALIDATION_RUN_STARTED", "VALIDATION_RUN_FAILED", "VALIDATION_RUN_RETRIED");

        // 최초 시작·실패는 첫 시도의 requestId로, 재시도는 새 requestId로 남아야
        // "어느 시도가 무엇을 했는지" 구분할 수 있다.
        assertThat(rows.get(0)).containsEntry("request_id", "request-first-attempt");
        assertThat(rows.get(1)).containsEntry("request_id", "request-first-attempt");
        assertThat(rows.get(2)).containsEntry("request_id", "request-retry-attempt");

        // triggeredBy(user_id)는 세 단계 모두 같은 사람이어야 한다(이 시나리오에서는).
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r.get("user_id")).longValue()).isEqualTo(3L));
    }
}
