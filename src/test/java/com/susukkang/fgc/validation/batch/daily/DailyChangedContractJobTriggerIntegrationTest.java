package com.susukkang.fgc.validation.batch.daily;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** 처리할 계약이 없는 날 일 배치가 빈 실행 행을 만들지 않게 하는 판단. Reader와 같은 조건을 본다. */
@SpringBootTest
@Transactional
class DailyChangedContractJobTriggerIntegrationTest {

    @Autowired
    private DailyChangedContractJobTrigger trigger;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reportsChangesOnlyWhenAContractChangedOrAnEventIsUnprocessed() {
        // 시드의 상태 사건은 일 배치 처리 기록이 없어 처음에는 처리 대상이다.
        assertThat(trigger.hasChangedContracts()).isTrue();

        jdbc.update("""
                INSERT INTO fgc.contract_status_event_processing
                    (contract_status_event_id, processing_job, processing_status)
                SELECT e.contract_status_event_id, ?, 'SUCCEEDED' FROM fgc.contract_status_event e
                 WHERE NOT EXISTS (
                     SELECT 1 FROM fgc.contract_status_event_processing p
                      WHERE p.contract_status_event_id = e.contract_status_event_id
                        AND p.processing_job = ? AND p.processing_status = 'SUCCEEDED')
                """, DailyChangedContractJobNames.JOB_NAME, DailyChangedContractJobNames.JOB_NAME);
        jdbc.update("""
                UPDATE fgc.batch_watermark SET last_processed_at = now() + interval '1 day'
                 WHERE job_name = ? AND step_name = ?
                """, DailyChangedContractJobNames.JOB_NAME, DailyChangedContractJobNames.CHANGED_CONTRACT_STEP_NAME);
        assertThat(trigger.hasChangedContracts()).isFalse();

        jdbc.update("""
                UPDATE fgc.batch_watermark SET last_processed_at = now() - interval '1 day'
                 WHERE job_name = ? AND step_name = ?
                """, DailyChangedContractJobNames.JOB_NAME, DailyChangedContractJobNames.CHANGED_CONTRACT_STEP_NAME);
        jdbc.update("""
                UPDATE fgc.insurance_contract SET updated_at = now()
                 WHERE contract_id = (SELECT min(contract_id) FROM fgc.insurance_contract)
                """);
        assertThat(trigger.hasChangedContracts()).isTrue();
    }
}
