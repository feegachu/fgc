package com.susukkang.fgc.validation.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #380 — BatchWatermarkRepository.advance를 실제 PostgreSQL로 검증한다.
 * WatermarkAdvanceStepListenerTest는 Repository를 mock으로 대체하므로 JPQL UPDATE 자체는 검증하지 못한다.
 */
@SpringBootTest
@Transactional
class BatchWatermarkRepositoryIntegrationTest {

    private static final String JOB = "WatermarkAdvanceTestJob";
    private static final String STEP = "testStep";

    @Autowired
    private BatchWatermarkRepository batchWatermarkRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seed() {
        jdbcTemplate.update("""
                DELETE FROM fgc.batch_watermark WHERE job_name = ?
                """, JOB);
        jdbcTemplate.update("""
                INSERT INTO fgc.batch_watermark (job_name, step_name, last_processed_at, processed_count)
                VALUES (?, ?, '2026-01-01T00:00:00Z', 5)
                """, JOB, STEP);
    }

    @Test
    void advanceUpdatesSeedRowAndAccumulatesProcessedCount() {
        seed();
        OffsetDateTime newMark = OffsetDateTime.parse("2026-09-01T00:00:00Z");

        int updated = batchWatermarkRepository.advance(JOB, STEP, newMark, 77L, 3L);

        assertThat(updated).isEqualTo(1);
        // JdbcTemplate으로 직접 읽어 영속성 컨텍스트 캐시가 아닌 DB 실제 값을 확인한다
        Long count = jdbcTemplate.queryForObject(
                "SELECT processed_count FROM fgc.batch_watermark WHERE job_name = ? AND step_name = ?",
                Long.class, JOB, STEP);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT last_run_id FROM fgc.batch_watermark WHERE job_name = ? AND step_name = ?",
                Long.class, JOB, STEP);
        Boolean marked = jdbcTemplate.queryForObject(
                "SELECT last_processed_at = ?::timestamptz AND last_success_at IS NOT NULL"
                        + " FROM fgc.batch_watermark WHERE job_name = ? AND step_name = ?",
                Boolean.class, newMark.toString(), JOB, STEP);
        assertThat(count).isEqualTo(8L);
        assertThat(runId).isEqualTo(77L);
        assertThat(marked).isTrue();
    }

    @Test
    void advanceReturnsZeroWhenSeedRowIsMissing() {
        int updated = batchWatermarkRepository.advance(
                "NoSuchJob", "noSuchStep", OffsetDateTime.parse("2026-09-01T00:00:00Z"), 1L, 1L);

        assertThat(updated).isZero();
    }
}
