package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 설명 : 한도 결과·상세 저장 실패와 계약별 독립 커밋을 실제 PostgreSQL에서 검증한다.
 * 계산만 고정하며 서비스 프록시·JPA 저장·DB 제약·실패 기록은 실제 구현을 사용한다.
 *
 * @author Codex
 * @since 2026-09-30
 * @version 1.0
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
class CapCheckTransactionIntegrationTest {
    @Autowired private CapCheckBatchItemService itemService;
    @Autowired private CapCheckFailureRecordService failureRecordService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private CapCalculator calculator;

    private Long runId;
    private Long contractId;
    private Long ruleId;
    private Long itemId;
    private CapCalculationCommand command;

    @BeforeEach
    void createCommittedRun() {
        contractId = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'", Long.class);
        ruleId = jdbc.queryForObject("SELECT MIN(cap_rule_set_id) FROM fgc.cap_rule_set WHERE payment_stage = 'GA_TO_FC'", Long.class);
        itemId = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'", Long.class);
        runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES ('2040-07-01', ?, 'MONTHLY') RETURNING validation_run_id
                """, Long.class, Math.floorMod(UUID.randomUUID().hashCode(), 1_000_000) + 10_000);
        command = new CapCalculationCommand(contractId, PaymentStage.GA_TO_FC,
                LocalDate.of(2026, 7, 31), CapCheckKind.MONTHLY, runId);
    }

    @Test
    void completedItemRemainsCommittedWhenOuterTransactionRollsBack() {
        when(calculator.calculate(command)).thenReturn(result(itemId));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            itemService.process(command);
            throw new OuterFailure();
        })).isInstanceOf(OuterFailure.class);

        assertThat(resultCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fgc.cap_check_detail d
                JOIN fgc.cap_check c USING (cap_check_id) WHERE c.validation_run_id = ?
                """, Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void detailConstraintFailureRollsBackHeaderAndIndependentFailureRecordSurvivesOuterRollback() {
        // 헤더 INSERT 후 상세 FK 실패를 일으켜 부분 저장이 남지 않는지 확인한다.
        when(calculator.calculate(command)).thenReturn(result(-1L));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> itemService.process(command))
                    .isInstanceOf(DataIntegrityViolationException.class);
            failureRecordService.record(runId, contractId, PaymentStage.GA_TO_FC, "detail FK failure");
            throw new OuterFailure();
        })).isInstanceOf(OuterFailure.class);

        assertThat(resultCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_occurrence
                 WHERE validation_run_id = ? AND reason_code = 'CAP_CALCULATION_FAILED'
                """, Integer.class, runId)).isEqualTo(1);

        // 앞 계약 처리의 rollback-only가 다음 독립 처리로 전파되지 않는다.
        when(calculator.calculate(command)).thenReturn(result(itemId));
        itemService.process(command);
        assertThat(resultCount()).isEqualTo(1);
    }

    private int resultCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fgc.cap_check WHERE validation_run_id = ?", Integer.class, runId);
    }

    private CapCalculationResult result(Long commissionItemId) {
        return new CapCalculationResult(contractId, PaymentStage.GA_TO_FC, CapCheckKind.MONTHLY,
                command.asOfDate(), ruleId, null, new BigDecimal("100000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1200000"), new BigDecimal("100"),
                new BigDecimal("1199900"), new BigDecimal("0.008333"), CapResultStatus.NORMAL,
                List.of(new CapCheckDetailLine(1, commissionItemId, "BASE_COMMISSION", "기본 수수료",
                        null, null, "INCLUDED", new BigDecimal("100"), "transaction regression", null)),
                Map.of("source", "issue-373-transaction-test"));
    }

    @AfterEach
    void removeOnlyThisRunsCommittedFixtures() {
        if (runId == null) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 격리된 테스트 DB에서만 append-only 기록을 정리하며 이번 실행 ID로 제한한다.
            jdbc.execute("SET LOCAL session_replication_role = replica");
            List<Long> caseIds = jdbc.queryForList("SELECT DISTINCT exception_case_id FROM fgc.exception_occurrence WHERE validation_run_id = ?", Long.class, runId);
            jdbc.update("DELETE FROM fgc.exception_occurrence WHERE validation_run_id = ?", runId);
            for (Long caseId : caseIds) {
                jdbc.update("DELETE FROM fgc.exception_case WHERE exception_case_id = ?", caseId);
            }
            jdbc.update("DELETE FROM fgc.cap_check_detail WHERE cap_check_id IN (SELECT cap_check_id FROM fgc.cap_check WHERE validation_run_id = ?)", runId);
            jdbc.update("DELETE FROM fgc.cap_check WHERE validation_run_id = ?", runId);
            jdbc.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", runId);
        });
    }

    private static class OuterFailure extends RuntimeException { }
}
