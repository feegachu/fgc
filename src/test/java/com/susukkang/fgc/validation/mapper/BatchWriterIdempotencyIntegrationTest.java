package com.susukkang.fgc.validation.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모든 배치 Writer가 UPSERT 또는 ON CONFLICT DO NOTHING 기반으로 동작하는지,
 * 재실행 시 업무 결과가 중복 생성되지 않는지 검증
 *
 * contract_status_event / contract_status_event_processing은 append-only라 명시적
 * DELETE로 정리할 수 없다(reject_update_delete 트리거) — @Transactional로 테스트 종료 시
 * 자동 롤백시켜, 실제 계약(FGC-FGL01-202607-0001)에 테스트용 이벤트 이력이 영구히
 * 남지 않도록 한다(코드리뷰 반영: 남은 이력은 DailyChangedContractJob의 재처리 대상
 * 판단에 영향을 준다).
 */
@SpringBootTest
@Transactional
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
