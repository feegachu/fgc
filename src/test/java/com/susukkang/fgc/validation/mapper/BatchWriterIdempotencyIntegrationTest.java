package com.susukkang.fgc.validation.mapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #77 "모든 배치 Writer가 UPSERT 또는 ON CONFLICT DO NOTHING 기반으로 동작하는지" /
 * "재실행 시 업무 결과가 중복 생성되지 않는지" 검증. DailyChangedContractJob의 두 Writer
 * 매퍼(ContractStatusEventProcessingMapper, ExceptionCaseMapper)를 실제 DB로 두 번씩
 * 호출해, "같은 입력을 다시 넣어도 중복 행이 안 생긴다"를 매퍼 레벨에서 직접 증명한다 —
 * ChangedContractItemWriterTest(mock)는 매퍼가 호출되는지만 보고, 이 매퍼가 실제로 DB
 * 제약과 맞물려 멱등적인지는 증명하지 못한다.
 */
@SpringBootTest
class BatchWriterIdempotencyIntegrationTest {

    private static final String JOB_NAME = "TestJob";

    @Autowired
    private ContractStatusEventProcessingMapper contractStatusEventProcessingMapper;
    @Autowired
    private ExceptionCaseMapper exceptionCaseMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contractId;
    private Long eventId;
    private Long validationRunId;

    @AfterEach
    void cleanUp() {
        if (validationRunId != null) {
            jdbcTemplate.update("DELETE FROM fgc.exception_case WHERE validation_run_id = ?", validationRunId);
            jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", validationRunId);
        }
        // contract_status_event / contract_status_event_processing은 append-only라
        // UPDATE/DELETE 자체가 트리거로 막혀 있다(reject_update_delete) — 지우지 않는다.
        // 테스트가 남기는 행은 소량이고, 다른 테스트의 조회 조건(contractId/eventId 특정)과
        // 겹치지 않아 해가 없다.
    }

    private Long seedContractId() {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, "FGC-FGL01-202607-0001");
    }

    private Long seedContractStatusEvent(Long contractId) {
        int eventSeq = 900000 + (int) (System.nanoTime() % 90000);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.contract_status_event
                    (contract_id, event_seq, new_status, effective_at, received_at,
                     source_system, source_event_key)
                VALUES (?, ?, 'LAPSED', now(), now(), 'TEST', ?)
                RETURNING contract_status_event_id
                """, Long.class, contractId, eventSeq, "idempotency-test-" + System.nanoTime());
    }

    private Long seedValidationRun() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'MANUAL_CONTRACT')
                RETURNING validation_run_id
                """, Long.class, LocalDate.of(2031, 4, 1), (int) (System.nanoTime() % 100000));
    }

    @Test
    void insertProcessingSucceededTwiceForSameEventAndJobLeavesOnlyOneRow() {
        contractId = seedContractId();
        eventId = seedContractStatusEvent(contractId);

        contractStatusEventProcessingMapper.insertProcessing(eventId, JOB_NAME, "SUCCEEDED", null, null);
        // 재실행 시나리오: 같은 이벤트를 같은 Job이 다시 SUCCEEDED로 기록하려 한다.
        contractStatusEventProcessingMapper.insertProcessing(eventId, JOB_NAME, "SUCCEEDED", null, null);

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.contract_status_event_processing
                 WHERE contract_status_event_id = ? AND processing_job = ? AND processing_status = 'SUCCEEDED'
                """, Integer.class, eventId, JOB_NAME);
        assertThat(count).isEqualTo(1);
    }

    // FAILED는 의도적으로 중복 방지 대상이 아니다 — 실패 이력은 재시도마다 쌓여야 감사 추적이 된다.
    @Test
    void insertProcessingFailedTwiceForSameEventAndJobAccumulatesBothRows() {
        contractId = seedContractId();
        eventId = seedContractStatusEvent(contractId);

        contractStatusEventProcessingMapper.insertProcessing(eventId, JOB_NAME, "FAILED", null, "첫 번째 실패");
        contractStatusEventProcessingMapper.insertProcessing(eventId, JOB_NAME, "FAILED", null, "두 번째 실패(재시도)");

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.contract_status_event_processing
                 WHERE contract_status_event_id = ? AND processing_job = ? AND processing_status = 'FAILED'
                """, Integer.class, eventId, JOB_NAME);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void insertDataQualityCaseTwiceForSameRunAndContractLeavesOnlyOneRow() {
        contractId = seedContractId();
        validationRunId = seedValidationRun();

        exceptionCaseMapper.insertDataQualityCase(validationRunId, contractId, "재검증 실패", "첫 시도");
        // 재실행 시나리오: 같은 실행·같은 계약에 대해 다시 데이터 품질 예외를 만들려 한다.
        exceptionCaseMapper.insertDataQualityCase(validationRunId, contractId, "재검증 실패", "재시도");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_case WHERE validation_run_id = ? AND contract_id = ?",
                Integer.class, validationRunId, contractId);
        assertThat(count).isEqualTo(1);
    }
}
